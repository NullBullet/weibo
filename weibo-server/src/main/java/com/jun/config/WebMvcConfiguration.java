package com.jun.config;

import com.jun.converter.StringToSearchTypeConverter;
import com.jun.converter.StringToTargetTypeConverter;
import com.jun.interceptor.JwtTokenUserInterceptor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

/**
 * 配置类，注册web层相关组件
 */
@Configuration
@Slf4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {
    @Resource
    private JwtTokenUserInterceptor jwtTokenUserInterceptor;
    @Resource
    private StringToSearchTypeConverter stringToSearchTypeConverter;
    @Resource
    private StringToTargetTypeConverter stringToTargetTypeConverter;

    protected void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/v1/**")
                .excludePathPatterns("/v1/auth/login")
                .excludePathPatterns("/v1/auth/captcha")
                .excludePathPatterns("/v1/auth/register")
                .excludePathPatterns("/v1/hot/topics")
                .excludePathPatterns("/v1/hot/posts");
    }

    public void addFormatters(FormatterRegistry registry) {
        log.info("开始注册转换器...");
        registry.addConverter(stringToSearchTypeConverter);
        registry.addConverter(stringToTargetTypeConverter);
    }
}
