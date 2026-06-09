package com.jun.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitListenerConfig {

    @Bean("likeFactory")
    public SimpleRabbitListenerContainerFactory likeFactory(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);

        // 设置JSON转换器
        factory.setMessageConverter(jsonMessageConverter);

        // 必须在这里显式设置，YAML对自定义Factory无效
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // 最小消费者数
        factory.setConcurrentConsumers(2);

        // 最大消费者数
        factory.setMaxConcurrentConsumers(5);

        // 公平分发
        factory.setPrefetchCount(1);

        return factory;
    }
}