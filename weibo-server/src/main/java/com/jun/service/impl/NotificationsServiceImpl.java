package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.enumeration.TargetType;
import com.jun.mapper.NotificationsMapper;
import com.jun.result.Result;
import com.jun.service.NotificationsService;
import com.jun.vo.LikeDetailVO;
import com.jun.vo.LikeNotificationVO;
import com.jun.vo.LikeUserVO;
import com.jun.vo.NotificationRawVO;
import com.jun.vo.SimpleUserVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationsServiceImpl implements NotificationsService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private NotificationsMapper notificationsMapper;

    @Override
    public Result getLikeInfo() {
        Long receiverId = BaseContext.getCurrentId();

        // 1. 查询聚合通知
        List<NotificationRawVO> rawList = notificationsMapper.getLikeNotifications(receiverId);
        if (rawList == null || rawList.isEmpty()) {
            return Result.success(new ArrayList<>());
        }

        // 2. 按 targetType 分组收集 targetId
        List<Integer> postIds = new ArrayList<>();
        List<Integer> commentIds = new ArrayList<>();
        for (NotificationRawVO raw : rawList) {
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
                notificationsMapper.getBatchRecentLikers(TargetType.POST.getCode(), postIds), 2);
        Map<String, List<SimpleUserVO>> commentLikersMap = buildLikersMap(
                notificationsMapper.getBatchRecentLikers(TargetType.COMMENT.getCode(), commentIds), 2);

        // 5. 组装 VO
        List<LikeNotificationVO> voList = new ArrayList<>();
        for (NotificationRawVO raw : rawList) {
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

        return Result.success(voList);
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
}
