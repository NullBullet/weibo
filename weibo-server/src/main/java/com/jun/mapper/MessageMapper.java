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
                              @Param("userId") Long userId);

    void resetUnreadCount(@Param("conversationId") Long conversationId,
                          @Param("userId") Long userId,
                          @Param("lastReadMessageId") Long lastReadMessageId);

    List<Long> getUserUnReadCount(Long userId);
    // ==================== PrivateMessage ====================

    void insertMessage(PrivateMessage message);

    Page<MessageVO> selectMessagesByPage(@Param("conversationId") Long conversationId);

    void updateMessagesRead(@Param("conversationId") Long conversationId,
                            @Param("userId") Long userId);

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
