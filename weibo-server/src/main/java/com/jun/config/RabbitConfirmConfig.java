package com.jun.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RabbitConfirmConfig {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
                    if (ack) {
                        log.info("消息发送成功:{}", correlationData);
                    } else {
                        log.error("消息发送失败:{}", cause);
                    }
                }
        );
    }
}