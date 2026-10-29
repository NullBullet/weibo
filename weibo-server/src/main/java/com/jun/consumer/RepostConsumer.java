package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.entity.Statuses;
import com.jun.mq.message.FeedMessage;
import com.jun.mq.message.RepostMessage;
import com.jun.service.PostService;
import com.jun.utils.HotScoreCalculator;
import com.jun.utils.PostStatsHelper;
import com.jun.utils.UserPostIdsHelper;
import com.jun.vo.RepostResultVO;
import com.jun.websocket.ChatWebSocketHandler;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.jun.config.RabbitMQConfig.REPOST_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.*;
import static java.lang.Long.parseLong;

@Component
@Slf4j
public class RepostConsumer {

    @Resource
    private PostService postService;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private UserPostIdsHelper userPostIdsHelper;
    @Resource
    private PostStatsHelper postStatsHelper;
    @Resource
    private ChatWebSocketHandler chatWebSocketHandler;

    /**
     * 统计字段安全解析：缺失或脏数据返回 0，不抛异常
     */
    private static long parseStatLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

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
            // 1. DB 事务：创建转发微博 + 原微博 reposts_count +1，任一步失败整体回滚
            Statuses forwardStatus = postService.saveForwardStatus(message);
            Long forwardStatusId = forwardStatus.getId();
            long createTimeStamp = forwardStatus.getCreatedAt().toEpochSecond(ZoneOffset.UTC);

            // 2. 数据库提交成功后再维护 Redis，避免 MQ 失败或 DB 回滚导致计数/热榜偏高
            maintainCacheAfterCommit(message, forwardStatusId, createTimeStamp);

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            // 落库成功后推送转发结果，前端据此确认转发成功（推送失败仅记录日志，不影响 ACK）
            notifyRepostResult(message, true, forwardStatusId);

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
                // 重试耗尽仍未落库，推送失败结果，前端据此提示转发失败
                notifyRepostResult(message, false, null);
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

    /**
     * 转发落库结果推送：成功携带新转发微博 ID；失败（重试耗尽进入死信）success=false。
     * 推送异常完全吞掉，绝不影响已完成的持久化与 ACK 流程。
     */
    private void notifyRepostResult(RepostMessage message, boolean success, Long forwardStatusId) {
        try {
            chatWebSocketHandler.sendNotification(
                    message.getUserId(),
                    RepostResultVO.builder()
                            .type(RepostResultVO.TYPE)
                            .success(success)
                            .originalStatusId(message.getOriginalStatusId())
                            .forwardStatusId(forwardStatusId)
                            .build(),
                    RepostResultVO.TYPE
            );
        } catch (Exception e) {
            log.warn("转发结果推送失败: userId={}, originalStatusId={}, forwardId={}",
                    message.getUserId(), message.getOriginalStatusId(), forwardStatusId, e);
        }
    }

    /**
     * 数据库提交后的 Redis 维护。缓存操作失败只记录日志，不回滚已提交数据，
     * 统计/时间线/收件箱均可由读路径从 DB 重建兜底。
     */
    private void maintainCacheAfterCommit(RepostMessage message, Long forwardStatusId, long createTimeStamp) {
        // 1. 初始化新转发微博的统计数据
        try {
            String statsKey = POST_STATS_KEY + forwardStatusId;
            Map<String, Object> map = new HashMap<>();
            map.put("likeCount", 0);
            map.put("commentCount", 0);
            map.put("viewCount", 0);
            map.put("forwardCount", 0);
            map.put("createTime", createTimeStamp);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                redisTemplate.opsForHash().putIfAbsent(statsKey, entry.getKey(), entry.getValue());
            }
            redisTemplate.expire(statsKey, POST_STATS_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("转发提交后初始化帖子统计缓存失败: forwardId={}", forwardStatusId, e);
        }

        // 2. 原微博转发数 +1，并同步更新热度榜
        try {
            Map<Object, Object> stats = postStatsHelper.incrementForwardCountAfterCommit(
                    message.getOriginalStatusId(), 1);
            updateHotScore(message.getOriginalStatusId(), stats);
        } catch (Exception e) {
            log.error("转发提交后更新原微博统计/热榜失败: originalId={}",
                    message.getOriginalStatusId(), e);
        }

        // 3. 写回转发者的用户帖子时间线（内部异常已捕获，读路径可重建）
        userPostIdsHelper.addPost(message.getUserId(), forwardStatusId, createTimeStamp);

        // 4. 提交后推送 Feed 扇出消息，粉丝收件箱由 FeedConsumer 异步维护
        try {
            FeedMessage feedMessage = FeedMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .userId(message.getUserId())
                    .statusId(forwardStatusId)
                    .createTime(createTimeStamp)
                    .retryCount(0)
                    .build();
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                    RabbitMQConfig.FEED_PUBLISH,
                    feedMessage
            );
        } catch (Exception e) {
            log.error("转发 Feed 消息发送失败: forwardId={}, userId={}",
                    forwardStatusId, message.getUserId(), e);
        }
    }

    /**
     * 用提交后的最新统计重算热度并写入排行榜。
     */
    private void updateHotScore(Long originalStatusId, Map<Object, Object> stats) {
        if (stats == null || stats.isEmpty()) {
            return;
        }

        long likeCount = parseStatLong(stats.get("likeCount"));
        long commentCount = parseStatLong(stats.get("commentCount"));
        long forwardCount = parseStatLong(stats.get("forwardCount"));
        long viewCount = parseStatLong(stats.get("viewCount"));
        long createTime = parseStatLong(stats.get("createTime"));
        if (createTime == 0) {
            createTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        }

        double score = HotScoreCalculator.calculate(viewCount, likeCount, commentCount, forwardCount, createTime);
        redisTemplate.opsForZSet().add(HOT_POST_ZSET_KEY, originalStatusId.toString(), score);
        redisTemplate.opsForSet().add(HOT_POST_CANDIDATE_KEY, originalStatusId.toString());
    }
}
