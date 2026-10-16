package com.jun.controller.v1.user;

import com.jun.dto.MessageQueryDTO;
import com.jun.dto.MessageReadDTO;
import com.jun.result.Result;
import com.jun.service.MessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/message")
@Slf4j
public class MessageController {

    @Resource
    private MessageService messageService;

    @GetMapping("/conversation/{targetUserId}")
    public Result getOrCreatConversation(@PathVariable Long targetUserId){
        log.info("创建或获取会话：{}",targetUserId);
        return messageService.getOrCreatConversation(targetUserId);
    }

    @GetMapping("/conversations")
    public Result getConversations(){
        log.info("获取会话列表");
        return messageService.getConversations();
    }

    @GetMapping("/conversation/detail/{conversationId}")
    public Result getConversationDetail(@PathVariable Long conversationId){
        log.info("获取会话详情：{}",conversationId);
        return messageService.getConversationDetail(conversationId);
    }

    @GetMapping("/history")
    public Result getHistory(MessageQueryDTO messageQueryDTO){
        log.info("获取聊天记录：{}",messageQueryDTO);
        return messageService.getHistory(messageQueryDTO);
    }

    @PostMapping("/read")
    public Result read(@RequestBody MessageReadDTO messageReadDTO){
        log.info("标记已读：{}",messageReadDTO);
        return messageService.read(messageReadDTO);
    }
}
