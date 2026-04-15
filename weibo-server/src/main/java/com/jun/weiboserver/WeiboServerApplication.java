package com.jun.weiboserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan(basePackages = "com.jun")
@SpringBootApplication(scanBasePackages = "com.jun")
public class WeiboServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeiboServerApplication.class, args);
    }

}
