package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.dto.CommentNotificationRawDTO;
import com.jun.enumeration.TargetType;
import com.jun.mapper.NotificationsMapper;
import com.jun.mapper.RewardActivityMapper;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.NotificationsService;
import com.jun.utils.NotificationLikeCacheHelper;
import com.jun.utils.NotificationUnreadCacheHelper;
import com.jun.vo.*;
import com.jun.websocket.WebSocketSessionManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.jun.constant.RedisConstants.LIKE_COMMENT_KEY;
import static com.jun.constant.RedisConstants.NOTIFICATION_LIKE_PAGE_SIZE;

@Slf4j
@Service
public class NotificationsServiceImpl implements NotificationsService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 游标内部字段分隔符
     */
    private static final String CURSOR_SEPARATOR = "|";

    @Resource
    private NotificationsMapper notificationsMapper;
    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private RewardActivityMapper rewardActivityMapper;
    @Resource
    private NotificationLikeCacheHelper notificationLikeCacheHelper;
    @Resource
    private NotificationUnreadCacheHelper notificationUnreadCacheHelper;
    @Resource
    private WebSocketSessionManager sessionManager;

    @Override
    public Result getLikeInfo(String cursor) {
        Long receiverId = BaseContext.getCurrentId();

        LikeCursor likeCursor;
        try {
            likeCursor = decodeCursor(cursor);
        } catch (IllegalArgumentException e) {
            return Result.error("非法的分页游标");
        }
        LocalDateTime cursorTime = likeCursor == null ? null : likeCursor.getLastLikeTime();
        Integer cursorId = likeCursor == null ? null : likeCursor.getNotificationId();

        // 1. 先查旁路缓存，命中直接返回（TTL 30 秒固定过期）
        LikeNotificationPageVO cachedPage = notificationLikeCacheHelper.get(
                receiverId, cursorTime, cursorId, NOTIFICATION_LIKE_PAGE_SIZE);
        if (cachedPage != null) {
            return Result.success(cachedPage);
        }

        // 2. 缓存未命中，多查一条用于判断是否还有下一页
        List<NotificationRawVO> rawList = notificationsMapper.getLikeNotifications(
                receiverId, cursorTime, cursorId, NOTIFICATION_LIKE_PAGE_SIZE + 1);
        if (rawList == null || rawList.isEmpty()) {
            LikeNotificationPageVO page = emptyPage();
            notificationLikeCacheHelper.put(
                    receiverId, cursorTime, cursorId, NOTIFICATION_LIKE_PAGE_SIZE, page);
            return Result.success(page);
        }

        boolean hasMore = rawList.size() > NOTIFICATION_LIKE_PAGE_SIZE;
        List<NotificationRawVO> pageRows = hasMore
                ? rawList.subList(0, NOTIFICATION_LIKE_PAGE_SIZE)
                : rawList;

        // 游标取本页最后一条原始记录：即使该通知内容已删除也要推进游标，避免下一页重复/卡住
        String nextCursor = null;
        if (hasMore) {
            NotificationRawVO lastRow = pageRows.get(pageRows.size() - 1);
            if (lastRow.getLastLikeTime() == null) {
                hasMore = false;
            } else {
                nextCursor = encodeCursor(lastRow);
            }
        }

        // 2. 按 targetType 分组收集 targetId
        List<Integer> postIds = new ArrayList<>();
        List<Integer> commentIds = new ArrayList<>();
        for (NotificationRawVO raw : pageRows) {
            if (raw.getLikeCount() == null || raw.getLikeCount() == 0) {
                continue;
            }
            if (raw.getTargetType() != null && raw.getTargetType() == TargetType.COMMENT.getCode()) {
                commentIds.add(raw.getTargetId());
            } else {
                postIds.add(raw.getTargetId());
            }
        }

        // 3. 批量查内容
        Map<Integer, String> postContentMap = buildContentMap(
                postIds.isEmpty() ? Collections.emptyList() : notificationsMapper.getPostContents(postIds));
        Map<Integer, String> commentContentMap = buildContentMap(
                commentIds.isEmpty() ? Collections.emptyList() : notificationsMapper.getCommentContents(commentIds));

        // 4. 批量查最近点赞用户
        Map<String, List<SimpleUserVO>> postLikersMap = buildLikersMap(
                postIds.isEmpty() ? Collections.emptyList() : notificationsMapper.getBatchRecentLikers(TargetType.POST.getCode(), postIds), 2);
        Map<String, List<SimpleUserVO>> commentLikersMap = buildLikersMap(
                commentIds.isEmpty() ? Collections.emptyList() : notificationsMapper.getBatchRecentLikers(TargetType.COMMENT.getCode(), commentIds), 2);

        // 5. 组装 VO
        List<LikeNotificationVO> voList = new ArrayList<>();
        for (NotificationRawVO raw : pageRows) {
            if (raw.getLikeCount() == null || raw.getLikeCount() == 0) {
                continue;
            }

            Integer dbTargetType = raw.getTargetType();
            Integer targetId = raw.getTargetId();
            boolean isComment = dbTargetType != null && dbTargetType == TargetType.COMMENT.getCode();

            // 取批量查好的内容
            String content = isComment
                    ? commentContentMap.get(targetId)
                    : postContentMap.get(targetId);

            // 跳过已删除的
            if (content == null) {
                continue;
            }

            // 取批量查好的最近点赞用户
            List<SimpleUserVO> recentUsers = isComment
                    ? commentLikersMap.getOrDefault(String.valueOf(targetId), new ArrayList<>())
                    : postLikersMap.getOrDefault(String.valueOf(targetId), new ArrayList<>());

            LikeNotificationVO vo = new LikeNotificationVO();
            vo.setNotificationId(raw.getNotificationId());
            vo.setTargetType(isComment ? "COMMENT" : "POST");
            vo.setTargetId(targetId);
            vo.setContent(content);
            vo.setLikeCount(raw.getLikeCount());
            vo.setRecentUsers(recentUsers);
            if (raw.getLastLikeTime() != null) {
                vo.setLastLikeTime(raw.getLastLikeTime().format(DTF));
            }

            voList.add(vo);
        }

        LikeNotificationPageVO page = new LikeNotificationPageVO();
        page.setRecords(voList);
        page.setNextCursor(nextCursor);
        page.setHasMore(hasMore);
        notificationLikeCacheHelper.put(
                receiverId, cursorTime, cursorId, NOTIFICATION_LIKE_PAGE_SIZE, page);
        return Result.success(page);
    }

    private LikeNotificationPageVO emptyPage() {
        LikeNotificationPageVO page = new LikeNotificationPageVO();
        page.setRecords(new ArrayList<>());
        page.setNextCursor(null);
        page.setHasMore(false);
        return page;
    }

    /**
     * 游标编码为 URL-safe Base64("lastLikeTime|notificationId")，前端无需感知排序字段。
     */
    private String encodeCursor(NotificationRawVO raw) {
        String value = raw.getLastLikeTime() + CURSOR_SEPARATOR + raw.getNotificationId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码游标；未传游标时返回 null，表示查询第一页。
     */
    private LikeCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separatorIndex = decoded.lastIndexOf(CURSOR_SEPARATOR);
            if (separatorIndex <= 0 || separatorIndex == decoded.length() - 1) {
                throw new IllegalArgumentException("游标格式不正确");
            }
            LocalDateTime lastLikeTime = LocalDateTime.parse(decoded.substring(0, separatorIndex));
            Integer notificationId = Integer.valueOf(decoded.substring(separatorIndex + 1));
            return new LikeCursor(lastLikeTime, notificationId);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法的分页游标", e);
        }
    }

    /**
     * 点赞通知游标：按 last_like_time + notificationId 复合排序，避免同一时间戳下分页丢失/重复。
     */
    private static final class LikeCursor {
        private final LocalDateTime lastLikeTime;
        private final Integer notificationId;

        private LikeCursor(LocalDateTime lastLikeTime, Integer notificationId) {
            this.lastLikeTime = lastLikeTime;
            this.notificationId = notificationId;
        }

        private LocalDateTime getLastLikeTime() {
            return lastLikeTime;
        }

        private Integer getNotificationId() {
            return notificationId;
        }
    }

    /**
     * 将 [{id=1, content="a"}, ...] 转为 {1 → "a", ...}
     */
    private Map<Integer, String> buildContentMap(List<Map<String, Object>> rows) {
        Map<Integer, String> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer id = row.get("id") != null ? ((Number) row.get("id")).intValue() : null;
            String content = row.get("content") != null ? row.get("content").toString() : null;
            if (id != null && content != null) {
                map.put(id, content);
            }
        }
        return map;
    }

    /**
     * 将批量查询结果 [{targetId=1, id=5, nickname="张三", avatar="..."}, ...]
     * 按 targetId 分组，每组取前 limit 个
     */
    private Map<String, List<SimpleUserVO>> buildLikersMap(List<Map<String, Object>> rows, int limit) {
        Map<String, List<SimpleUserVO>> map = new LinkedHashMap<>();
        if (rows == null) return map;

        for (Map<String, Object> row : rows) {
            Object tidObj = row.get("targetId");
            String tid = tidObj != null ? String.valueOf(((Number) tidObj).intValue()) : null;
            if (tid == null) continue;

            List<SimpleUserVO> list = map.computeIfAbsent(tid, k -> new ArrayList<>());
            if (list.size() < limit) {
                SimpleUserVO vo = new SimpleUserVO();
                vo.setId(row.get("id") != null ? ((Number) row.get("id")).intValue() : null);
                vo.setNickname(row.get("nickname") != null ? row.get("nickname").toString() : null);
                vo.setAvatar(row.get("avatar") != null ? row.get("avatar").toString() : null);
                list.add(vo);
            }
        }
        return map;
    }

    @Override
    public Result getCommentInfo() {
        Long receiverId = BaseContext.getCurrentId();

        // 1. 查询评论通知
        List<CommentNotificationRawDTO> rawList = notificationsMapper.getCommentNotifications(receiverId);
        if (rawList == null || rawList.isEmpty()) {
            return Result.success(new ArrayList<>());
        }

        // 2. 收集 IDs（使用 Set 去重）
        Set<Long> senderIds = new HashSet<>();
        Set<Long> commentIds = new HashSet<>();
        Set<Long> postTargetIds = new HashSet<>();
        Set<Long> commentTargetIds = new HashSet<>();

        for (CommentNotificationRawDTO raw : rawList) {
            if (raw.getSenderId() != null) senderIds.add(raw.getSenderId());
            if (raw.getCommentId() != null) commentIds.add(raw.getCommentId());
            if (raw.getTargetId() != null) {
                if (Objects.equals(raw.getTargetType(), TargetType.COMMENT.getCode())) {
                    commentTargetIds.add(raw.getTargetId());
                } else {
                    postTargetIds.add(raw.getTargetId());
                }
            }
        }

        // 3. 批量查发送者信息
        //    getSenderInfos 返回 [{id, nickname, avatar}, ...] 用 id 做 key 构建 Map
        List<Map<String, Object>> senderRows = senderIds.isEmpty()
                ? Collections.emptyList()
                : notificationsMapper.getSenderInfos(senderIds);
        Map<Long, SenderVO> senderMap = buildSenderMapById(senderRows);

        // 4. 批量查评论内容
        List<Long> cidList = new ArrayList<>(commentIds);
        List<Map<String, Object>> commentRows = cidList.isEmpty()
                ? Collections.emptyList()
                : notificationsMapper.getCommentContents(
                cidList.stream().map(Long::intValue).toList());
        Map<Long, String> commentContentMap = buildContentMapById(commentRows);

        // 5. 批量查目标内容（帖子 + 评论）
        List<Long> ptidList = new ArrayList<>(postTargetIds);
        List<Map<String, Object>> postRows = ptidList.isEmpty()
                ? Collections.emptyList()
                : notificationsMapper.getPostContents(
                ptidList.stream().map(Long::intValue).toList());
        Map<Long, String> postContentMap = buildContentMapById(postRows);

        List<Long> ctidList = new ArrayList<>(commentTargetIds);
        List<Map<String, Object>> ctRows = ctidList.isEmpty()
                ? Collections.emptyList()
                : notificationsMapper.getCommentContents(
                ctidList.stream().map(Long::intValue).toList());
        Map<Long, String> commentTargetContentMap = buildContentMapById(ctRows);

        // 6. 组装 VO
        List<CommentNotificationVO> voList = new ArrayList<>();
        for (CommentNotificationRawDTO raw : rawList) {
            // 发送者
            SenderVO sender = senderMap.get(raw.getSenderId());
            if (sender == null) continue; // 用户已删除

            // 评论内容
            String content = raw.getCommentId() != null
                    ? commentContentMap.get(raw.getCommentId()) : null;
            if (content == null) continue; // 评论已删除

            // 目标内容
            String targetContent;
            if (Objects.equals(raw.getTargetType(), TargetType.COMMENT.getCode())) {
                targetContent = raw.getTargetId() != null
                        ? commentTargetContentMap.get(raw.getTargetId()) : null;
            } else {
                targetContent = raw.getTargetId() != null
                        ? postContentMap.get(raw.getTargetId()) : null;
            }
            if (targetContent == null) continue; // 目标已删除

            CommentNotificationVO vo = new CommentNotificationVO();
            vo.setNotificationId(raw.getNotificationId() != null
                    ? raw.getNotificationId().intValue() : null);
            vo.setSender(sender);
            vo.setTargetType(Objects.equals(raw.getTargetType(), TargetType.COMMENT.getCode())
                    ? "COMMENT" : "POST");
            vo.setTargetId(raw.getTargetId() != null ? raw.getTargetId().intValue() : null);
            vo.setCommentId(raw.getCommentId() != null ? raw.getCommentId().intValue() : null);
            vo.setContent(content);
            vo.setTargetContent(targetContent);
            vo.setCreateTime(raw.getCreateTime() != null ? raw.getCreateTime().format(DTF) : null);
            vo.setIsRead(raw.getIsRead());

            voList.add(vo);
        }

        // 6. 批量查询当前用户是否已点赞每条评论
        Long currentUserId = BaseContext.getCurrentId();
        List<String> likeKeys = new ArrayList<>();
        for (CommentNotificationVO vo : voList) {
            if (vo.getCommentId() != null) {
                likeKeys.add(LIKE_COMMENT_KEY + vo.getCommentId());
            }
        }
        if (!likeKeys.isEmpty()) {
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : likeKeys) {
                    connection.setCommands().sIsMember(
                            key.getBytes(),
                            currentUserId.toString().getBytes()
                    );
                }
                return null;
            });
            for (int i = 0; i < voList.size(); i++) {
                if (voList.get(i).getCommentId() != null && i < results.size()) {
                    Boolean isMember = (Boolean) results.get(i);
                    voList.get(i).setIsLiked(isMember != null && isMember);
                } else {
                    voList.get(i).setIsLiked(false);
                }
            }
        } else {
            for (CommentNotificationVO vo : voList) {
                vo.setIsLiked(false);
            }
        }

        return Result.success(voList);
    }

    /**
     * 将 [{id, nickname, avatar}, ...] 转为 {id → SenderVO, ...}
     */
    private Map<Long, SenderVO> buildSenderMapById(List<Map<String, Object>> rows) {
        Map<Long, SenderVO> map = new HashMap<>();
        if (rows == null) return map;
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("id");
            if (idObj == null) continue;
            Long id = ((Number) idObj).longValue();
            SenderVO vo = new SenderVO();
            vo.setId(id.intValue());
            vo.setNickname(row.get("nickname") != null ? row.get("nickname").toString() : null);
            vo.setAvatar(row.get("avatar") != null ? row.get("avatar").toString() : null);
            map.put(id, vo);
        }
        return map;
    }

    /**
     * 将 [{id, content}, ...] 转为 {id → content, ...}，key 为 Long
     */
    private Map<Long, String> buildContentMapById(List<Map<String, Object>> rows) {
        Map<Long, String> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("id");
            Object contentObj = row.get("content");
            if (idObj != null && contentObj != null) {
                map.put(((Number) idObj).longValue(), contentObj.toString());
            }
        }
        return map;
    }

    @Override
    public Result getLikeDetail(Integer targetId) {
        // 1. 根据 targetId 查 attitudes 表获取 targetType
        Integer dbTargetType = notificationsMapper.getTargetTypeByTargetId(targetId);
        if (dbTargetType == null) {
            return Result.success(new LikeDetailVO());
        }

        // 2. 根据 targetType 取对应内容
        List<Integer> idList = List.of(targetId);
        Map<Integer, String> contentMap = dbTargetType == TargetType.COMMENT.getCode()
                ? buildContentMap(notificationsMapper.getCommentContents(idList))
                : buildContentMap(notificationsMapper.getPostContents(idList));
        String content = contentMap.get(targetId);

        // 3. 点赞用户列表
        List<LikeUserVO> users = notificationsMapper.getLikeUsers(dbTargetType, targetId);
        if (users == null) {
            users = new ArrayList<>();
        }

        // 4. 统计点赞数
        Integer total = notificationsMapper.countLike(dbTargetType, targetId);
        if (total == null) total = 0;

        // 5. 组装
        LikeDetailVO detail = new LikeDetailVO();
        detail.setTargetId(targetId);
        detail.setContent(content);
        detail.setTotal(total);
        detail.setUsers(users);

        return Result.success(detail);
    }

    @Override
    public Result getSystemNotifications(Integer page, Integer pageSize) {
        Long userId = BaseContext.getCurrentId();
        if (page == null || page < 1) page = 1;
        if (pageSize == null || pageSize < 1) pageSize = 20;

        int offset = (page - 1) * pageSize;
        Integer total = rewardActivityMapper.countSystemNotifications(userId);
        if (total == null) total = 0;

        List<SystemNotificationVO> records = rewardActivityMapper.getSystemNotifications(userId, offset, pageSize);
        if (records == null) records = Collections.emptyList();

        PageResult pageResult = new PageResult();
        pageResult.setTotal(total);
        pageResult.setRecords(records);
        return Result.success(pageResult);
    }

    @Override
    public Result getUnreadCount() {
        return Result.success(notificationUnreadCacheHelper.get(BaseContext.getCurrentId()));
    }

    @Override
    public Long getTotalUnreadCount(Long userId) {
        return totalOf(notificationUnreadCacheHelper.get(userId));
    }

    @Override
    public void pushTotalUnreadCount(Long userId) {
        try {
            // 未读数已发生变化：按 MySQL 重算并续期旁路缓存，再用最新值推送
            UnreadCountVO counts = notificationUnreadCacheHelper.refresh(userId);
            sessionManager.sendUnreadCount(userId, totalOf(counts));
        } catch (Exception e) {
            log.warn("推送总未读数失败，userId={}", userId, e);
        }
    }

    @Override
    public void refreshUnreadCountAfterCommit(Long userId) {
        notificationUnreadCacheHelper.refreshAfterCommit(userId);
    }

    private Long totalOf(UnreadCountVO counts) {
        return counts.getChat() + counts.getComment() + counts.getLike() + counts.getSystem();
    }

    @Override
    public Result markAllCommentNotificationsRead() {
        Long receiverId = BaseContext.getCurrentId();
        int rows = notificationsMapper.markAllCommentNotificationsRead(receiverId);
        // 未读状态已变化：重算并续期统一未读数缓存
        notificationUnreadCacheHelper.refreshAfterCommit(receiverId);
        return Result.success(rows);
    }

    @Override
    public Result markAllLikeNotificationsRead() {
        Long receiverId = BaseContext.getCurrentId();
        int rows = notificationsMapper.markAllLikeNotificationsRead(receiverId);
        // 未读状态已变化：重算并续期统一未读数缓存
        notificationUnreadCacheHelper.refreshAfterCommit(receiverId);
        return Result.success(rows);
    }

    @Override
    public Result markAllSystemNotificationsRead() {
        Long userId = BaseContext.getCurrentId();
        int rows = rewardActivityMapper.markAllNotificationsRead(userId);
        // 未读状态已变化：重算并续期统一未读数缓存
        notificationUnreadCacheHelper.refreshAfterCommit(userId);
        return Result.success(rows);
    }
}
