package com.jun.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RabbitReturnConfig {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        rabbitTemplate.setReturnsCallback(returned -> {
                    log.error(
                            "消息路由失败！exchange={}, routingKey={}, message={}",
                            returned.getExchange(),
                            returned.getRoutingKey(),
                            returned.getMessage()
                    );
                }
        );
    }
}