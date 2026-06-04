package com.jun.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    public static final String LIKE_QUEUE = "like.queue";

    //死信队列
    public static final String LIKE_DLQ = "like.dlq";
    //死信交换机
    public static final String LIKE_DLX = "like.dlx";
    //死信RoutingKey
    public static final String LIKE_DLQ_ROUTING_KEY = "like.dlq";

    public static final String LIKE_RETRY_QUEUE = "like.retry.queue";
    public static final Integer MAX_RETRY_COUNT = 3;

    //Topic交换机
    public static final String WEIBO_TOPIC_EXCHANGE = "weibo.topic";

    //帖子点赞
    public static final String POST_LIKE = "post.like";
    //取消点赞
    public static final String POST_CANCEL = "post.cancel";

    @Bean
    public Queue likeQueue() {
        //durable（持久化），RabbitMQ 重启后队列不会消失
        //return new Queue(LIKE_QUEUE, true);

        Map<String, Object> args = new HashMap<>();

        // 指定死信交换机
        args.put("x-dead-letter-exchange", LIKE_DLX);
        // 指定死信RoutingKey
        args.put("x-dead-letter-routing-key", LIKE_DLQ_ROUTING_KEY);

        return new Queue(
                LIKE_QUEUE,
                true,
                false,
                false,
                args
        );
    }

    //死信交换机
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(LIKE_DLX);
    }

    //死信队列
    @Bean
    public Queue deadLetterQueue() {
        return new Queue(LIKE_DLQ, true);
    }

    //死信绑定
    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder
                .bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(LIKE_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue retryQueue() {

        Map<String, Object> args = new HashMap<>();

        // 5秒过期
        args.put("x-message-ttl", 5000);

        // 过期后回到正常队列
        args.put("x-dead-letter-exchange", "");

        args.put(
                "x-dead-letter-routing-key",
                LIKE_QUEUE
        );

        return new Queue(
                LIKE_RETRY_QUEUE,
                true,
                false,
                false,
                args
        );
    }

    /**
     * Topic交换机
     */
    @Bean
    public TopicExchange weiboTopicExchange() {
        return new TopicExchange(WEIBO_TOPIC_EXCHANGE);
    }

    /**
     * 监听所有帖子行为
     */
    @Bean
    public Binding postBinding() {
        return BindingBuilder
                .bind(likeQueue())
                .to(weiboTopicExchange())
                .with("post.*");
    }
    /**
     * JSON 序列化，MQ 能直接传 Java 对象
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}