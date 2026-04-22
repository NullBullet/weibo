package com.jun.service;

import com.jun.result.Result;
import org.springframework.web.multipart.MultipartFile;

public interface UploadService {
    Result<String> uploadStatusImage(MultipartFile multipartFile);
}
