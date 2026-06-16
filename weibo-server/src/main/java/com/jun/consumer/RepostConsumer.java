package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.entity.Statuses;
import com.jun.mapper.PostMapper;
import com.jun.mq.message.RepostMessage;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.jun.config.RabbitMQConfig.REPOST_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.*;

@Component
@Slf4j
public class RepostConsumer {

    @Resource
    private PostMapper postMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.REPOST_QUEUE,
            containerFactory = "repostFactory")
    public void handleRepost(RepostMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等：检查消息是否已处理
        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("转发消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();

            // 1. 创建新的转发微博（写入 MySQL，获取自增 ID）
            Statuses forwardStatus = Statuses.builder()
                    .userId(message.getUserId())
                    .content(message.getContent())
                    .statusType(1)
                    .originalStatusId(message.getOriginalStatusId())
                    .createdAt(now)
                    .build();
            postMapper.saveForwardStatus(forwardStatus);

            Long forwardStatusId = forwardStatus.getId();
            long createTimeStamp = now.toEpochSecond(ZoneOffset.UTC);

            // 2. 写入用户 timeline ZSET
            String zsetKey = USER_POST_IDS_KEY + message.getUserId();
            redisTemplate.opsForZSet().add(zsetKey, forwardStatusId.toString(), createTimeStamp);

            // 3. 初始化新转发微博的统计数据
            String statsKey = POST_STATS_KEY + forwardStatusId;
            Map<String, Object> map = new HashMap<>();
            map.put("likeCount", 0);
            map.put("commentCount", 0);
            map.put("viewCount", 0);
            map.put("forwardCount", 0);
            map.put("createTime", createTimeStamp);
            redisTemplate.opsForHash().putAll(statsKey, map);

            // 4. 原微博 reposts_count +1
            postMapper.updateStatusRepostCount(1, message.getOriginalStatusId());

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            // 手动ACK
            channel.basicAck(deliveryTag, false);
            log.info("转发消息消费成功: forwardId={}, originalId={}",
                    forwardStatusId, message.getOriginalStatusId());

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("转发消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= REPOST_MAX_RETRY_COUNT) {
                log.error("转发达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.REPOST_RETRY_QUEUE,
                        message
                );
            }
            channel.basicAck(deliveryTag, false);
        }
    }
}
