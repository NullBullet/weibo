package com.jun.mapper;

import com.github.pagehelper.Page;
import com.jun.entity.Conversation;
import com.jun.entity.ConversationUser;
import com.jun.entity.PrivateMessage;
import com.jun.vo.ConversationVO;
import com.jun.vo.MessageVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageMapper {

    // ==================== Conversation ====================

    @Select("select id from conversation where user1_id = #{user1} and user2_id = #{user2}")
    Long getConversation(@Param("user1") Long user1, @Param("user2") Long user2);

    Conversation selectConversationById(@Param("id") Long id);

    List<ConversationVO> selectConversationList(@Param("userId") Long userId);

    void insertConversation(Conversation conversation);

    void updateConversationLastMessage(@Param("conversationId") Long conversationId,
                                       @Param("lastMessage") String lastMessage,
                                       @Param("lastMessageTime") java.time.LocalDateTime lastMessageTime);

    // ==================== ConversationUser ====================

    void insertConversationUser(ConversationUser conversationUser);

    void incrementUnreadCount(@Param("conversationId") Long conversationId,
                              @Param("userId") Long userId,
                              @Param("messageId") Long messageId);

    void decreaseUnreadCount(@Param("conversationId") Long conversationId,
                             @Param("userId") Long userId,
                             @Param("markedCount") int markedCount,
                             @Param("lastMessageId") Long lastMessageId);

    List<Long> getUserUnReadCount(Long userId);

    @Select("select coalesce(sum(unread_count), 0) from conversation_user where user_id = #{userId}")
    Long countUnreadMessages(@Param("userId") Long userId);

    // ==================== PrivateMessage ====================

    void insertMessage(PrivateMessage message);

    Page<MessageVO> selectMessagesByPage(@Param("conversationId") Long conversationId);

    int updateMessagesRead(@Param("conversationId") Long conversationId,
                           @Param("userId") Long userId,
                           @Param("lastMessageId") Long lastMessageId);

    @Select("select id from private_message where conversation_id = #{conversationId} order by create_time desc limit 1")
    Long selectLatestMessageId(@Param("conversationId") Long conversationId);

    List<MessageVO> selectUnreadMessages(@Param("userId") Long userId);

    PrivateMessage selectBySenderAndClientMessageId(
            @Param("senderId") Long senderId,
            @Param("clientMessageId") String clientMessageId
    );

    PrivateMessage selectBySenderAndClientMessageIdForUpdate(
            @Param("senderId") Long senderId,
            @Param("clientMessageId") String clientMessageId
    );
}
