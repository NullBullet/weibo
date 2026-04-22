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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.jun.constant.MessageConstant.STATUS_PUBLISH_FAIL;

@Slf4j
@Service
public class StatusesServiceImpl implements StatusesService {

    @Resource
    private StatusesMapper statusesMapper;

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
        return Result.success(saveStatusVO);
    }
}
