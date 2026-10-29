package com.jun.service.impl;

import com.jun.config.RabbitMQConfig;
import com.jun.context.BaseContext;
import com.jun.dto.RepostDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.entity.Statuses;
import com.jun.enumeration.TargetType;
import com.jun.mapper.PostMapper;
import com.jun.mq.message.LikeMessage;
import com.jun.mq.message.RepostMessage;
import com.jun.result.Result;
import com.jun.service.ActivityService;
import com.jun.service.PostService;
import com.jun.utils.HotScoreCalculator;
import com.jun.utils.LikeSetHelper;
import com.jun.utils.PostStatsHelper;
import com.jun.utils.UserStatsHelper;
import com.jun.vo.ToggleLikeVO;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static com.jun.config.RabbitMQConfig.*;
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
    @Resource
    private PostStatsHelper postStatsHelper;
    @Resource
    private UserStatsHelper userStatsHelper;
    @Resource
    private LikeSetHelper likeSetHelper;

    /**
     * 统计字段安全解析：缺失或脏数据返回 0，不抛异常
     */
    private static long parseStatLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

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

        // 防御：统计缓存缺失时先重建，避免 HINCRBY 在 key 丢失时创建残缺 hash；
        // 用户统计必须在下方 updateStatistics（DB 写）之前重建，避免重建读到未提交计数导致双重加
        postStatsHelper.ensureStats(statusId);
        userStatsHelper.ensureStats(postOwnerId);
        // 防御：点赞集合缓存缺失时先从 attitudes 表重建，保证 SADD/SREM 返回值反映
        // 数据库真实状态——否则已点赞用户再次点赞会被当作新赞，likeCount 重复累加并
        // 重复落库；取消点赞会被误判为幂等，DB 记录无法删除
        likeSetHelper.ensureLikeSet(TargetType.POST, statusId);

        if (Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())) {
            Long add = redisTemplate.opsForSet().add(LIKE_POST_KEY + statusId, userId.toString());
            if (add == 1) {
                Long likeCount = redisTemplate.opsForHash().increment(postStatsKey, "likeCount", 1);
                // 点赞写入成功，续期点赞集合 TTL
                likeSetHelper.refreshTtl(TargetType.POST, statusId);
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
                    // 统计值修改成功，续期缓存 TTL
                    userStatsHelper.refreshTtl(postOwnerId);
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
                // 取消点赞写入成功，续期点赞集合 TTL
                likeSetHelper.refreshTtl(TargetType.POST, statusId);
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
                    // 统计值修改成功，续期缓存 TTL
                    userStatsHelper.refreshTtl(postOwnerId);
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
        if (postStats == null || postStats.isEmpty()) {
            // 统计缓存缺失（可能正在重建中），跳过本次热度更新，避免 NPE；读路径会自动重建
            return Result.success(toggleLikeVO);
        }
        Long likeCount1 = parseStatLong(postStats.get("likeCount"));
        Long commentCount = parseStatLong(postStats.get("commentCount"));
        Long forwardCount = parseStatLong(postStats.get("forwardCount"));
        Long viewCount = parseStatLong(postStats.get("viewCount"));
        Long createTime = parseStatLong(postStats.get("createTime"));
        // 兜底时间戳与 HotScoreCalculator/初始化处保持同一约定（本地时间按 UTC 秒）
        if (createTime == 0) {
            createTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        }

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

        // MQ 异步入库：创建转发微博 + 更新原微博 reposts_count
        // Redis 转发统计、热榜、用户时间线和 Feed 统一由消费者在数据库提交后维护
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

    @Transactional
    @Override
    public Statuses saveForwardStatus(RepostMessage message) {
        // 防御：在 DB 更新前建立原微博统计缓存基线，提交后只做增量，避免重建读到本次变更后重复累加
        postStatsHelper.ensureStats(message.getOriginalStatusId());

        Statuses forwardStatus = Statuses.builder()
                .userId(message.getUserId())
                .content(message.getContent())
                .statusType(1)
                .originalStatusId(message.getOriginalStatusId())
                .createdAt(LocalDateTime.now())
                .build();

        postMapper.saveForwardStatus(forwardStatus);
        postMapper.updateStatusRepostCount(1, message.getOriginalStatusId());
        return forwardStatus;
    }
}
