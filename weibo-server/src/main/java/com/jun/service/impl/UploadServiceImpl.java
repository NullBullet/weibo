package com.jun.service.impl;

import com.jun.exception.UploadAvatarException;
import com.jun.result.Result;
import com.jun.service.UploadService;
import com.jun.utils.OssUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static com.jun.constant.MessageConstant.UPLOAD_IMAGE_ERROR;

@Slf4j
@Service
public class UploadServiceImpl implements UploadService {

    @Resource
    private OssUtil ossUtil;

    @Override
    public Result<String> uploadStatusImage(MultipartFile multipartFile) {
        String fileUrl = null;
        if (multipartFile != null && !multipartFile.isEmpty()) {
            try {
                String folder = "weibo/posts/";
                String dataPath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM/dd/"));
                String originalFilename = multipartFile.getOriginalFilename();
                String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
                String objectName = folder + dataPath + UUID.randomUUID() + extension;
                fileUrl = ossUtil.upload(multipartFile.getInputStream(), objectName);
            } catch (Exception e) {
                log.error("文件上传失败");
                throw new UploadAvatarException(UPLOAD_IMAGE_ERROR);
            }
        } else {
            log.error("图片为null");
            throw new UploadAvatarException(UPLOAD_IMAGE_ERROR);
        }
        return Result.success(fileUrl);
    }
}
