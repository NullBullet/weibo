package com.jun.controller.v1.user;

import com.jun.result.Result;
import com.jun.service.UploadService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/v1/upload")
public class UploadController {

    @Resource
    private UploadService uploadService;

    @PostMapping("/image")
    public Result<String> image(MultipartFile multipartFile) {
        log.info("上传微博图片：{}", multipartFile);
        return uploadService.uploadStatusImage(multipartFile);
    }
}
