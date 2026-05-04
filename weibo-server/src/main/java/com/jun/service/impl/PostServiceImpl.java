package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.dto.ToggleLikeDTO;
import com.jun.mapper.PostMapper;
import com.jun.result.Result;
import com.jun.service.PostService;
import com.jun.utils.HotScoreCalculator;
import com.jun.vo.ToggleLikeVO;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static com.jun.constant.RedisConstants.*;
import static java.lang.Long.parseLong;

@Service
public class PostServiceImpl implements PostService {

    @Resource
    private PostMapper postMapper;
    @Resource
    private RedisTemplate redisTemplate;

    //todo 未保证redis原子性，MQ异步落库
    @Transactional
    @Override
    public Result toggleLike(ToggleLikeDTO toggleLikeDTO) {
        Long userId = BaseContext.getCurrentId();
        toggleLikeDTO.setUserId(userId);
        Long statusId = toggleLikeDTO.getTargetId();
        String postStatsKey = POST_STATS_KEY + statusId;
        if (Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())) {
            Long add = redisTemplate.opsForSet().add(LIKE_POST_KEY + statusId, userId.toString());
            if(add==1){
                Long likeCount = redisTemplate.opsForHash().increment(postStatsKey, "likeCount", 1);
                postMapper.saveLikeInfo(toggleLikeDTO);
                postMapper.updateStatusLikeCount(1,statusId);
                postMapper.updateStatistics(1,userId);

                ToggleLikeVO toggleLikeVO = ToggleLikeVO.builder().likeCount(likeCount).isLiked(true).build();

                redisTemplate.opsForHash().put(postStatsKey,"likeCount",likeCount);

                return updateHotScore(postStatsKey, statusId, toggleLikeVO);
            }
        }
        else {
            Long removed = redisTemplate.opsForSet().remove(LIKE_POST_KEY + statusId, userId.toString());

            if (removed == 1) {
                Long likeCount = redisTemplate.opsForHash().increment(postStatsKey, "likeCount", -1);
                postMapper.deleteLikeInfo(toggleLikeDTO);
                postMapper.updateStatusLikeCount(-1, statusId);
                postMapper.updateStatistics(-1,userId);

                ToggleLikeVO toggleLikeVO = ToggleLikeVO.builder().likeCount(likeCount).isLiked(false).build();

                redisTemplate.opsForHash().put(postStatsKey,"likeCount",likeCount);

                return updateHotScore(postStatsKey, statusId, toggleLikeVO);
            }
        }
        return Result.error("点赞/取关失败");
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
            // 如果没有任何互动，则从候选池和热度榜中移除
            redisTemplate.opsForSet().remove(HOT_POST_CANDIDATE_KEY, statusId.toString());
            redisTemplate.opsForZSet().remove(HOT_POST_ZSET_KEY, statusId.toString());
            return Result.success(toggleLikeVO);
        }

        double score = HotScoreCalculator.calculate(viewCount, likeCount1, commentCount, forwardCount, createTime);
        redisTemplate.opsForZSet().add(HOT_POST_ZSET_KEY, statusId.toString(),score);
        redisTemplate.opsForSet().add(HOT_POST_CANDIDATE_KEY, statusId.toString());

        return Result.success(toggleLikeVO);
    }
}
