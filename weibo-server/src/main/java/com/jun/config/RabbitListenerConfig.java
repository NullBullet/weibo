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

    @Bean("commentFactory")
    public SimpleRabbitListenerContainerFactory commentFactory(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        //自己定义的工厂类不会自动继承默认配置
        // 设置JSON转换器
        factory.setMessageConverter(jsonMessageConverter);
        // 必须在这里显式设置，YAML对自定义Factory无效
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // 评论写入频率低于点赞，配置较少的消费者
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(3);
        factory.setPrefetchCount(1);
        return factory;
    }

    @Bean("commentLikeFactory")
    public SimpleRabbitListenerContainerFactory commentLikeFactory(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        factory.setMessageConverter(jsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // 评论点赞频率与评论发布相近，配置一致
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(3);
        factory.setPrefetchCount(1);
        return factory;
    }

    @Bean("feedFactory")
    public SimpleRabbitListenerContainerFactory feedFactory(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        factory.setMessageConverter(jsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // Feed推送涉及大量粉丝 ZSET 写入，使用较多消费者
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(5);
        factory.setPrefetchCount(1);
        return factory;
    }

    @Bean("loginLogFactory")
    public SimpleRabbitListenerContainerFactory loginLogFactory(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        factory.setMessageConverter(jsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // 登录日志写入量不大，配置较少消费者
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        factory.setPrefetchCount(1);
        return factory;
    }

    @Bean("repostFactory")
    public SimpleRabbitListenerContainerFactory repostFactory(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        factory.setMessageConverter(jsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // 转发写入频率更低，配置较少消费者
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        factory.setPrefetchCount(1);
        return factory;
    }
}