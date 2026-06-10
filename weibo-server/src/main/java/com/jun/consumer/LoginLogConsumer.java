package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.entity.LoginLog;
import com.jun.mapper.LoginLogMapper;
import com.jun.mq.message.LoginLogMessage;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static com.jun.config.RabbitMQConfig.LOGIN_LOG_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.ACTIVE_USERS_KEY;
import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class LoginLogConsumer {

    @Resource
    private LoginLogMapper loginLogMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.LOGIN_LOG_QUEUE,
            containerFactory = "loginLogFactory")
    public void handleLoginLog(LoginLogMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等：检查消息是否已处理
        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("登录日志消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // DB：写入登录日志（使用 Service 层传入的时间戳，与 Redis 保持一致）
            LoginLog loginLog = new LoginLog();
            loginLog.setUserId(message.getUserId());
            loginLog.setLoginType(message.getLoginType());
            loginLog.setIpAddress(message.getIpAddress());
            loginLog.setUserAgent(message.getUserAgent());
            loginLog.setLoginStatus(message.getLoginStatus());
            loginLog.setFailReason(message.getFailReason());
            loginLog.setCreatedAt(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(message.getActiveTimestamp()),
                    ZoneOffset.UTC
            ));

            loginLogMapper.insert(loginLog);

            // Redis：登录成功时写入活跃用户 ZSET
            if (message.getLoginStatus() == 1) {
                redisTemplate.opsForZSet().add(
                        ACTIVE_USERS_KEY,
                        message.getUserId().toString(),
                        (double) message.getActiveTimestamp()
                );
            }

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            // 手动ACK
            channel.basicAck(deliveryTag, false);
            log.info("登录日志消费成功: userId={}, status={}, reason={}",
                    message.getUserId(), message.getLoginStatus(), message.getFailReason());

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("登录日志消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= LOGIN_LOG_MAX_RETRY_COUNT) {
                log.error("登录日志达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.LOGIN_LOG_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}
