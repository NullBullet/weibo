package com.jun.config;

import com.jun.properties.OssProperties;
import com.jun.utils.OssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class OssConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public OssUtil ossUtil(OssProperties ossProperties) {
        log.info("创建阿里云文件上传工具类对象");
        return new OssUtil(ossProperties.getEndpoint(), ossProperties.getAccessKeyId(),
                ossProperties.getAccessKeySecret(), ossProperties.getBucketName());
    }
}
