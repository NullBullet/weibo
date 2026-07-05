package com.jun.service.impl;

import com.jun.config.RabbitMQConfig;
import com.jun.context.BaseContext;
import com.jun.dto.RepostDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.mapper.PostMapper;
import com.jun.mq.message.LikeMessage;
import com.jun.mq.message.RepostMessage;
import com.jun.result.Result;
import com.jun.service.ActivityService;
import com.jun.service.PostService;
import com.jun.utils.HotScoreCalculator;
import com.jun.vo.ToggleLikeVO;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static com.jun.config.RabbitMQConfig.POST_CANCEL;
import static com.jun.config.RabbitMQConfig.POST_LIKE;
import static com.jun.config.RabbitMQConfig.POST_REPOST;
import static com.jun.constant.RedisConstants.*;
import static java.lang.Long.parseLong;

@Service
public class PostServiceImpl implements PostService {

    @Resource
    private PostMapper postMapper;
    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private ActivityService activityService;
    @Resource
    private RabbitTemplate rabbitTemplate;

    //todo 未保证redis原子性，MQ异步落库
    @Transactional
    @Override
    public Result togglePostLike(ToggleLikeDTO toggleLikeDTO) {
        Long userId = BaseContext.getCurrentId();
        toggleLikeDTO.setUserId(userId);
        Long statusId = toggleLikeDTO.getTargetId();

        // 检查帖子是否已被删除（getUserIdByStatusId 已加 is_deleted = 0 过滤）
        Long postOwnerId = postMapper.getUserIdByStatusId(statusId);
        if (postOwnerId == null) {
            return Result.error("该微博已被删除");
        }

        String postStatsKey = POST_STATS_KEY + statusId;

        if (Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())) {
            Long add = redisTemplate.opsForSet().add(LIKE_POST_KEY + statusId, userId.toString());
            if (add == 1) {
                Long likeCount = redisTemplate.opsForHash().increment(postStatsKey, "likeCount", 1);
                //postMapper.saveLikeInfo(toggleLikeDTO);
                //postMapper.updateStatusLikeCount(1, statusId);
                LikeMessage message = LikeMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .userId(userId)
                        .statusId(statusId)
                        .targetType(toggleLikeDTO.getTargetType())
                        .retryCount(0)
                        .isLiked(true)
                        .build();

                CorrelationData correlationData = new CorrelationData(message.getMessageId());

                String routingKey = Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())
                        ? POST_LIKE
                        : POST_CANCEL;

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                        routingKey,
                        message,
                        correlationData
                );

                // 帖子作者的获赞数 +1（DB + Redis）
                if (postOwnerId != null) {
                    postMapper.updateStatistics(1, postOwnerId);
                    redisTemplate.opsForHash().increment(USER_STATS_KEY + postOwnerId, "likesCount", 1);
                }

                ToggleLikeVO toggleLikeVO = ToggleLikeVO.builder().likeCount(likeCount).isLiked(true).build();
                return updateHotScore(postStatsKey, statusId, toggleLikeVO);
            }
            // add == 0 表示已点赞，直接返回成功
            if (add == 0) {
                Object likeCount = redisTemplate.opsForHash().get(postStatsKey, "likeCount");
                long count = likeCount == null ? 0 : parseLong(likeCount.toString());
                return Result.success(ToggleLikeVO.builder().likeCount(count).isLiked(true).build());
            }
        } else {
            Long removed = redisTemplate.opsForSet().remove(LIKE_POST_KEY + statusId, userId.toString());
            if (removed == 1) {
                // Redis 计数减 1，但保证不小于 0
                Long likeCount = redisTemplate.opsForHash().increment(postStatsKey, "likeCount", -1);
                if (likeCount < 0) {
                    likeCount = 0L;
                    redisTemplate.opsForHash().put(postStatsKey, "likeCount", 0);
                }
                //postMapper.deleteLikeInfo(toggleLikeDTO);
                //postMapper.updateStatusLikeCount(-1, statusId);
                LikeMessage message = LikeMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .userId(userId)
                        .statusId(statusId)
                        .targetType(toggleLikeDTO.getTargetType())
                        .retryCount(0)
                        .isLiked(false)
                        .build();

                CorrelationData correlationData = new CorrelationData(message.getMessageId());

                String routingKey = Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())
                        ? POST_LIKE
                        : POST_CANCEL;

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                        routingKey,
                        message,
                        correlationData
                );

                // 帖子作者的获赞数 -1（DB + Redis）
                if (postOwnerId != null) {
                    postMapper.updateStatistics(-1, postOwnerId);
                    redisTemplate.opsForHash().increment(USER_STATS_KEY + postOwnerId, "likesCount", -1);
                }

                ToggleLikeVO toggleLikeVO = ToggleLikeVO.builder().likeCount(likeCount).isLiked(false).build();
                return updateHotScore(postStatsKey, statusId, toggleLikeVO);
            }
            // removed == 0 表示已取消点赞，直接返回成功
            if (removed == 0) {
                Object likeCount = redisTemplate.opsForHash().get(postStatsKey, "likeCount");
                long count = likeCount == null ? 0 : parseLong(likeCount.toString());
                return Result.success(ToggleLikeVO.builder().likeCount(count).isLiked(false).build());
            }
        }
        return Result.error("点赞/取消失败");
    }

    private @NonNull Result<ToggleLikeVO> updateHotScore(String postStatsKey, Long statusId, ToggleLikeVO toggleLikeVO) {
        Map postStats = redisTemplate.opsForHash().entries(postStatsKey);
        Long likeCount1 = parseLong(postStats.get("likeCount").toString());
        Long commentCount = parseLong(postStats.get("commentCount").toString());
        Long forwardCount = parseLong(postStats.get("forwardCount").toString());
        Long viewCount = parseLong(postStats.get("viewCount").toString());
        Long createTime = parseLong(postStats.get("createTime").toString());

        boolean hasValidInteraction = (likeCount1 > 0 || commentCount > 0 || forwardCount > 0);

        if (!hasValidInteraction) {
            redisTemplate.opsForSet().remove(HOT_POST_CANDIDATE_KEY, statusId.toString());
            redisTemplate.opsForZSet().remove(HOT_POST_ZSET_KEY, statusId.toString());
            return Result.success(toggleLikeVO);
        }

        double score = HotScoreCalculator.calculate(viewCount, likeCount1, commentCount, forwardCount, createTime);
        redisTemplate.opsForZSet().add(HOT_POST_ZSET_KEY, statusId.toString(), score);
        redisTemplate.opsForSet().add(HOT_POST_CANDIDATE_KEY, statusId.toString());

        return Result.success(toggleLikeVO);
    }

    @Transactional
    @Override
    public Result repost(RepostDTO repostDTO) {
        Long currentId = BaseContext.getCurrentId();
        //查询被转发微博
        Long forwardId = repostDTO.getPostId();

        // 检查被转发微博是否存在且未被删除
        Long exists = postMapper.checkStatusExists(forwardId);
        if (exists == null) {
            return Result.error("该微博已被删除");
        }

        //获取 OriginalStatusId（处理转发链）
        Long originalStatus = postMapper.getOriginalStatus(forwardId);
        Long originalStatusId = (originalStatus == null) ? forwardId : originalStatus;

        // Redis 同步：原微博 repost_count +1
        String postStatusKey = POST_STATS_KEY + originalStatusId;
        redisTemplate.opsForHash().increment(postStatusKey, "forwardCount", 1);

        //更新热度排行榜
        Map postStats = redisTemplate.opsForHash().entries(postStatusKey);
        Long likeCount1 = parseLong(postStats.get("likeCount").toString());
        Long commentCount = parseLong(postStats.get("commentCount").toString());
        Long forwardCount = parseLong(postStats.get("forwardCount").toString());
        Long viewCount = parseLong(postStats.get("viewCount").toString());
        Long createTime = parseLong(postStats.get("createTime").toString());

        double score = HotScoreCalculator.calculate(viewCount, likeCount1, commentCount, forwardCount, createTime);
        redisTemplate.opsForZSet().add(HOT_POST_ZSET_KEY, originalStatusId.toString(), score);
        redisTemplate.opsForSet().add(HOT_POST_CANDIDATE_KEY, originalStatusId.toString());

        // MQ 异步入库：创建转发微博 + 更新原微博 reposts_count
        RepostMessage message = RepostMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .userId(currentId)
                .originalStatusId(originalStatusId)
                .content(repostDTO.getContent())
                .retryCount(0)
                .build();

        CorrelationData correlationData = new CorrelationData(message.getMessageId());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                POST_REPOST,
                message,
                correlationData
        );

        //活动系统
        activityService.handleForwardActivity(originalStatusId, currentId, null);

        return Result.success();
    }
}
