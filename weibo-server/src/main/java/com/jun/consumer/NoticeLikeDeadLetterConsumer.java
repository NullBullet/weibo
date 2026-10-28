package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.mq.message.LikeMessage;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class NoticeLikeDeadLetterConsumer {

    @RabbitListener(queues = RabbitMQConfig.NOTICE_POST_LIKE_DLQ)
    public void handleDeadLetter(LikeMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        log.error("收到帖子点赞通知死信消息：{}", message);

        // todo: 保存DB记录 / 发送告警

        channel.basicAck(deliveryTag, false);
    }
}
