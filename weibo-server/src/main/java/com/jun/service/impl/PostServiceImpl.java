package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.dto.ToggleLikeDTO;
import com.jun.mapper.PostMapper;
import com.jun.result.Result;
import com.jun.service.PostService;
import com.jun.vo.ToggleLikeVO;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.jun.constant.RedisConstants.LIKE_COUNT_POST_KEY;
import static com.jun.constant.RedisConstants.LIKE_POST_KEY;

@Service
public class PostServiceImpl implements PostService {

    @Resource
    private PostMapper postMapper;
    @Resource
    private RedisTemplate redisTemplate;

    //todo 未保证原子性，MQ异步落库
    @Transactional
    @Override
    public Result toggleLike(ToggleLikeDTO toggleLikeDTO) {
        Long userId = BaseContext.getCurrentId();
        toggleLikeDTO.setUserId(userId);
        Long statusId = toggleLikeDTO.getTargetId();
        if (Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())) {

            Long add = redisTemplate.opsForSet().add(LIKE_POST_KEY + statusId, userId.toString());
            if(add==1){
                Long likeCount = redisTemplate.opsForValue().increment(LIKE_COUNT_POST_KEY+statusId) ;
                postMapper.saveLikeInfo(toggleLikeDTO);
                postMapper.updateStatusLikeCount(likeCount,statusId);
                ToggleLikeVO toggleLikeVO = ToggleLikeVO.builder().likeCount(likeCount).isLiked(true).build();
                return Result.success(toggleLikeVO);
            }
        }
        else {
            Long removed = redisTemplate.opsForSet().remove(LIKE_POST_KEY + statusId, userId.toString());

            if (removed == 1) {
                Long likeCount = redisTemplate.opsForValue().decrement(LIKE_COUNT_POST_KEY+statusId);
                postMapper.deleteLikeInfo(toggleLikeDTO);
                postMapper.updateStatusLikeCount(likeCount, statusId);

                ToggleLikeVO toggleLikeVO = ToggleLikeVO.builder().likeCount(likeCount).isLiked(false).build();
                return Result.success(toggleLikeVO);
            }
        }
        return Result.error("点赞/取关失败");
    }
}
