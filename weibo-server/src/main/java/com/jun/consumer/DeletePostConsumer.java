package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.mapper.CommentsMapper;
import com.jun.mapper.StatusesMapper;
import com.jun.mq.message.DeletePostMessage;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.jun.config.RabbitMQConfig.DELETE_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.*;

@Component
@Slf4j
public class DeletePostConsumer {

    @Resource
    private StatusesMapper statusesMapper;
    @Resource
    private CommentsMapper commentsMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.DELETE_QUEUE,
            containerFactory = "deletePostFactory")
    public void handleDeletePost(DeletePostMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等：检查消息是否已处理
        String idempotentKey = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(idempotentKey);
        if (Boolean.TRUE.equals(exists)) {
            log.info("删除微博消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            Long statusId = message.getStatusId();
            Long userId = message.getUserId();

            // ==================== Redis 清理 ====================

            // 1. 帖子相关 Redis
            redisTemplate.delete(POST_DETAIL_KEY + statusId);
            redisTemplate.delete(POST_STATS_KEY + statusId);
            redisTemplate.delete(LIKE_POST_KEY + statusId);

            // 2. 从 user:post:ids 移除
            redisTemplate.opsForZSet().remove(USER_POST_IDS_KEY + userId, statusId.toString());

            // 3. 从热度榜移除
            redisTemplate.opsForZSet().remove(HOT_POST_ZSET_KEY, statusId.toString());
            redisTemplate.opsForSet().remove(HOT_POST_CANDIDATE_KEY, statusId.toString());

            // 4. 清理帖子下的评论相关 Redis 数据
            var commentRecords = statusesMapper.getCommentsByStatusId(statusId);
            if (commentRecords != null && !commentRecords.isEmpty()) {
                for (var record : commentRecords) {
                    Object commentIdObj = record.get("id");
                    if (commentIdObj == null) continue;
                    String cid = commentIdObj.toString();

                    redisTemplate.delete(LIKE_COMMENT_KEY + cid);
                    redisTemplate.delete(COMMENT_STATS_KEY + cid);

                    // 删除回复列表缓存
                    String replyPagesKey = COMMENT_REPLY_LIST_KEY + cid + ":pages";
                    Set<Object> replyPages = redisTemplate.opsForSet().members(replyPagesKey);
                    if (replyPages != null) {
                        for (Object p : replyPages) {
                            redisTemplate.delete(COMMENT_REPLY_LIST_KEY + cid + ":" + p);
                        }
                    }
                    redisTemplate.delete(replyPagesKey);
                    redisTemplate.delete(COMMENT_REPLY_LIST_KEY + cid + ":1:3");
                    redisTemplate.delete(COMMENT_REPLY_LIST_KEY + cid + ":1:5");
                    redisTemplate.delete(COMMENT_REPLY_LIST_KEY + cid + ":1:10");
                    redisTemplate.delete(COMMENT_REPLY_LIST_KEY + cid + ":1:20");
                }
            }

            // 5. 删除评论列表缓存
            String commentPagesKey = POST_COMMENT_LIST_KEY + statusId + ":pages";
            Set<Object> commentPages = redisTemplate.opsForSet().members(commentPagesKey);
            if (commentPages != null) {
                for (Object p : commentPages) {
                    redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":" + p);
                }
            }
            redisTemplate.delete(commentPagesKey);
            redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":1:3");
            redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":1:5");
            redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":1:10");
            redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":1:20");

            // ==================== DB 软删除 ====================

            // 6. DB：软删除微博
            statusesMapper.softDeleteStatus(statusId);

            // 7. DB：软删除该微博下的所有评论
            commentsMapper.softDeleteCommentsByStatusId(statusId);

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(idempotentKey, "1", 7, TimeUnit.DAYS);

            // 手动ACK
            channel.basicAck(deliveryTag, false);
            log.info("删除微博消费成功: statusId={}, userId={}", statusId, userId);

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("删除微博消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= DELETE_MAX_RETRY_COUNT) {
                log.error("删除微博达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.DELETE_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}