package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.dto.RepostDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.entity.Statuses;
import com.jun.mapper.PostMapper;
import com.jun.result.Result;
import com.jun.service.ActivityService;
import com.jun.service.PostService;
import com.jun.utils.HotScoreCalculator;
import com.jun.vo.ToggleLikeVO;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

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

    //todo 未保证redis原子性，MQ异步落库
    @Transactional
    @Override
    public Result togglePostLike(ToggleLikeDTO toggleLikeDTO) {
        Long userId = BaseContext.getCurrentId();
        toggleLikeDTO.setUserId(userId);
        Long statusId = toggleLikeDTO.getTargetId();
        String postStatsKey = POST_STATS_KEY + statusId;

        // 获取被点赞帖子的发布者，用于维护 COUNT_USER_LIKES_KEY
        Long postOwnerId = postMapper.getUserIdByStatusId(statusId);

        if (Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())) {
            Long add = redisTemplate.opsForSet().add(LIKE_POST_KEY + statusId, userId.toString());
            if (add == 1) {
                Long likeCount = redisTemplate.opsForHash().increment(postStatsKey, "likeCount", 1);
                postMapper.saveLikeInfo(toggleLikeDTO);
                postMapper.updateStatusLikeCount(1, statusId);
                // 帖子作者的获赞数 +1（DB + Redis）
                if (postOwnerId != null) {
                    postMapper.updateStatistics(1, postOwnerId);
                    redisTemplate.opsForValue().increment(COUNT_USER_LIKES_KEY + postOwnerId);
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
                postMapper.deleteLikeInfo(toggleLikeDTO);
                postMapper.updateStatusLikeCount(-1, statusId);
                // 帖子作者的获赞数 -1（DB + Redis）
                if (postOwnerId != null) {
                    postMapper.updateStatistics(-1, postOwnerId);
                    redisTemplate.opsForValue().decrement(COUNT_USER_LIKES_KEY + postOwnerId);
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

    //todo MQ异步落库
    @Transactional
    @Override
    public Result repost(RepostDTO repostDTO) {
        Long currentId = BaseContext.getCurrentId();
        //查询被转发微博
        Long forwardId = repostDTO.getPostId();
        //获取 OriginalStatusId
        Long originalStatusId = null;
        Long originalStatus = postMapper.getOriginalStatus(forwardId);

        originalStatusId = (originalStatus == null) ? forwardId : originalStatus;

        LocalDateTime now = LocalDateTime.now();
        //创建新的转发微博
        Statuses forwardStatus = Statuses.builder()
                .userId(currentId)
                .content(repostDTO.getContent())
                .statusType(1)
                .originalStatusId(originalStatusId)
                .createdAt(now)
                .build();
        //写入 MySQL
        postMapper.saveForwardStatus(forwardStatus);
        //写入用户 timeline ZSET
        String key = USER_POST_IDS_KEY + currentId;
        long createTimeStamp = now.toEpochSecond(ZoneOffset.UTC);
        Long forwardStatusId = forwardStatus.getId();
        redisTemplate.opsForZSet().add(key, forwardStatusId.toString(), createTimeStamp);
        //原微博 repost_count +1
        String forwardPostStatusKey = POST_STATS_KEY + forwardStatusId;

        Map<String, Object> map = new HashMap<>();
        map.put("likeCount", 0);
        map.put("commentCount", 0);
        map.put("viewCount", 0);
        map.put("createTime", createTimeStamp);
        redisTemplate.opsForHash().putAll(forwardPostStatusKey, map);

        String postStatusKey = POST_STATS_KEY + originalStatusId;
        postMapper.updateStatusRepostCount(1, originalStatusId);
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

        //活动系统
        activityService.handleForwardActivity(originalStatusId, currentId, forwardStatusId);

        return Result.success();
    }
}
