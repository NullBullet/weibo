package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.dto.StatusesImageDTO;
import com.jun.entity.Statuses;
import com.jun.exception.StatusPublishException;
import com.jun.mapper.StatusesMapper;
import com.jun.result.Result;
import com.jun.service.StatusesService;
import com.jun.vo.SaveStatusVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.jun.constant.MessageConstant.STATUS_PUBLISH_FAIL;
import static com.jun.constant.RedisConstants.*;

@Slf4j
@Service
public class StatusesServiceImpl implements StatusesService {

    @Resource
    private StatusesMapper statusesMapper;
    @Resource
    private RedisTemplate redisTemplate;

    @Transactional
    @Override
    public Result saveStatus(StatusesImageDTO statusesImageDTO) {
        Long userId = BaseContext.getCurrentId();
        SaveStatusVO saveStatusVO = new SaveStatusVO();
        saveStatusVO.setContent(statusesImageDTO.getContent());

        int isSuccess1 = statusesMapper.saveStatus(userId,saveStatusVO);
        Long statusId = saveStatusVO.getId();
        if (isSuccess1 <=0) {
            log.error("微博id获取异常");
            throw new StatusPublishException(STATUS_PUBLISH_FAIL);
        }
        if (statusesImageDTO.getImages() != null && !statusesImageDTO.getImages().isEmpty()) {
            int isSuccess2 = statusesMapper.saveStatusImages(statusesImageDTO, statusId);
            if (isSuccess2 <= 0) {
                log.error("图片保存异常");
                throw new StatusPublishException(STATUS_PUBLISH_FAIL);
            }
        }
        saveStatusVO = statusesMapper.getStatusById(statusId);

        String postStatsKey = POST_STATS_KEY + statusId;
        Map<String, Object> map = new HashMap<>();
        map.put("likeCount",0);
        map.put("commentCount",0);
        map.put("forwardCount",0);
        map.put("viewCount",0);
        long createTime = saveStatusVO.getCreatedAt().toEpochSecond(ZoneOffset.UTC);
        map.put("createTime", createTime);
        redisTemplate.opsForHash().putAll(postStatsKey,map);

        String userPostIdsKey = USER_POST_IDS_KEY + userId;
        redisTemplate.opsForZSet().add(userPostIdsKey,statusId.toString(),createTime);

        // 推送到粉丝收件箱
        String fansKey = FOLLOW_FANS_KEY + userId;
        Set<String> fans = redisTemplate.opsForSet().members(fansKey);
        if (fans != null && !fans.isEmpty()) {
            for (String fanId : fans) {
                String feedKey = FEED_USER_KEY + fanId;
                redisTemplate.opsForZSet().add(feedKey, statusId.toString(), createTime);
            }
        }

        return Result.success(saveStatusVO);
    }
}
