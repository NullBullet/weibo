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

    // ======================== 评论队列 ========================

    public static final String COMMENT_QUEUE = "comment.queue";
    public static final String COMMENT_DLQ = "comment.dlq";
    public static final String COMMENT_DLX = "comment.dlx";
    public static final String COMMENT_DLQ_ROUTING_KEY = "comment.dlq";
    public static final String COMMENT_RETRY_QUEUE = "comment.retry.queue";
    public static final Integer COMMENT_MAX_RETRY_COUNT = 3;

    //评论行为
    public static final String COMMENT_CREATE = "comment.create";

    // ======================== 评论点赞队列 ========================

    public static final String COMMENT_LIKE_QUEUE = "comment.like.queue";
    public static final String COMMENT_LIKE_DLQ = "comment.like.dlq";
    public static final String COMMENT_LIKE_DLX = "comment.like.dlx";
    public static final String COMMENT_LIKE_DLQ_ROUTING_KEY = "comment.like.dlq";
    public static final String COMMENT_LIKE_RETRY_QUEUE = "comment.like.retry.queue";
    public static final Integer COMMENT_LIKE_MAX_RETRY_COUNT = 3;

    //评论点赞行为
    public static final String COMMENT_LIKE = "comment.like.execute";
    public static final String COMMENT_LIKE_CANCEL = "comment.like.cancel";

    // ======================== Feed推送队列 ========================

    public static final String FEED_QUEUE = "feed.queue";
    public static final String FEED_DLQ = "feed.dlq";
    public static final String FEED_DLX = "feed.dlx";
    public static final String FEED_DLQ_ROUTING_KEY = "feed.dlq";
    public static final String FEED_RETRY_QUEUE = "feed.retry.queue";
    public static final Integer FEED_MAX_RETRY_COUNT = 3;

    //Feed推送行为
    public static final String FEED_PUBLISH = "feed.publish";

    // ======================== 转发队列 ========================

    public static final String REPOST_QUEUE = "repost.queue";
    public static final String REPOST_DLQ = "repost.dlq";
    public static final String REPOST_DLX = "repost.dlx";
    public static final String REPOST_DLQ_ROUTING_KEY = "repost.dlq";
    public static final String REPOST_RETRY_QUEUE = "repost.retry.queue";
    public static final Integer REPOST_MAX_RETRY_COUNT = 3;

    //转发行为
    public static final String POST_REPOST = "post.repost";

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

    // ======================== 评论队列 Bean ========================

    @Bean
    public Queue commentQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", COMMENT_DLX);
        args.put("x-dead-letter-routing-key", COMMENT_DLQ_ROUTING_KEY);
        return new Queue(COMMENT_QUEUE, true, false, false, args);
    }

    @Bean
    public DirectExchange commentDeadLetterExchange() {
        return new DirectExchange(COMMENT_DLX);
    }

    @Bean
    public Queue commentDeadLetterQueue() {
        return new Queue(COMMENT_DLQ, true);
    }

    @Bean
    public Binding commentDeadLetterBinding() {
        return BindingBuilder
                .bind(commentDeadLetterQueue())
                .to(commentDeadLetterExchange())
                .with(COMMENT_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue commentRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", 5000);
        args.put("x-dead-letter-exchange", "");
        args.put("x-dead-letter-routing-key", COMMENT_QUEUE);
        return new Queue(COMMENT_RETRY_QUEUE, true, false, false, args);
    }

    /**
     * 监听评论行为
     */
    @Bean
    public Binding commentBinding() {
        return BindingBuilder
                .bind(commentQueue())
                .to(weiboTopicExchange())
                .with("comment.*");
    }

    // ======================== 评论点赞队列 Bean ========================

    @Bean
    public Queue commentLikeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", COMMENT_LIKE_DLX);
        args.put("x-dead-letter-routing-key", COMMENT_LIKE_DLQ_ROUTING_KEY);
        return new Queue(COMMENT_LIKE_QUEUE, true, false, false, args);
    }

    @Bean
    public DirectExchange commentLikeDeadLetterExchange() {
        return new DirectExchange(COMMENT_LIKE_DLX);
    }

    @Bean
    public Queue commentLikeDeadLetterQueue() {
        return new Queue(COMMENT_LIKE_DLQ, true);
    }

    @Bean
    public Binding commentLikeDeadLetterBinding() {
        return BindingBuilder
                .bind(commentLikeDeadLetterQueue())
                .to(commentLikeDeadLetterExchange())
                .with(COMMENT_LIKE_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue commentLikeRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", 5000);
        args.put("x-dead-letter-exchange", "");
        args.put("x-dead-letter-routing-key", COMMENT_LIKE_QUEUE);
        return new Queue(COMMENT_LIKE_RETRY_QUEUE, true, false, false, args);
    }

    /**
     * 监听评论点赞行为（3级routing key，避免与 comment.* 冲突）
     */
    @Bean
    public Binding commentLikeBinding() {
        return BindingBuilder
                .bind(commentLikeQueue())
                .to(weiboTopicExchange())
                .with("comment.like.*");
    }

    // ======================== Feed推送队列 Bean ========================

    @Bean
    public Queue feedQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", FEED_DLX);
        args.put("x-dead-letter-routing-key", FEED_DLQ_ROUTING_KEY);
        return new Queue(FEED_QUEUE, true, false, false, args);
    }

    @Bean
    public DirectExchange feedDeadLetterExchange() {
        return new DirectExchange(FEED_DLX);
    }

    @Bean
    public Queue feedDeadLetterQueue() {
        return new Queue(FEED_DLQ, true);
    }

    @Bean
    public Binding feedDeadLetterBinding() {
        return BindingBuilder
                .bind(feedDeadLetterQueue())
                .to(feedDeadLetterExchange())
                .with(FEED_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue feedRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", 5000);
        args.put("x-dead-letter-exchange", "");
        args.put("x-dead-letter-routing-key", FEED_QUEUE);
        return new Queue(FEED_RETRY_QUEUE, true, false, false, args);
    }

    /**
     * 监听Feed推送行为
     */
    @Bean
    public Binding feedBinding() {
        return BindingBuilder
                .bind(feedQueue())
                .to(weiboTopicExchange())
                .with("feed.publish");
    }

    // ======================== 转发队列 Bean ========================

    /**
     * 监听转发行为
     */
    @Bean
    public Binding repostBinding() {
        return BindingBuilder
                .bind(repostQueue())
                .to(weiboTopicExchange())
                .with("post.repost");
    }

    @Bean
    public Queue repostQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", REPOST_DLX);
        args.put("x-dead-letter-routing-key", REPOST_DLQ_ROUTING_KEY);
        return new Queue(REPOST_QUEUE, true, false, false, args);
    }

    @Bean
    public DirectExchange repostDeadLetterExchange() {
        return new DirectExchange(REPOST_DLX);
    }

    @Bean
    public Queue repostDeadLetterQueue() {
        return new Queue(REPOST_DLQ, true);
    }

    @Bean
    public Binding repostDeadLetterBinding() {
        return BindingBuilder
                .bind(repostDeadLetterQueue())
                .to(repostDeadLetterExchange())
                .with(REPOST_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue repostRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", 5000);
        args.put("x-dead-letter-exchange", "");
        args.put("x-dead-letter-routing-key", REPOST_QUEUE);
        return new Queue(REPOST_RETRY_QUEUE, true, false, false, args);
    }

    /**
     * JSON 序列化，MQ 能直接传 Java 对象
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}