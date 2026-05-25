package com.jun.service.impl;

import cn.hutool.json.JSONUtil;
import com.jun.context.BaseContext;
import com.jun.dto.CommentsResultDTO;
import com.jun.dto.PostCommentsDTO;
import com.jun.dto.RepliesCommentsResultDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.entity.Comment;
import com.jun.mapper.CommentsMapper;
import com.jun.mapper.PostMapper;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.CommentsService;
import com.jun.utils.HotScoreCalculator;
import com.jun.vo.CommentVO;
import com.jun.vo.ReplyCountVO;
import com.jun.vo.ToggleLikeVO;
import com.jun.vo.UserVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.jun.constant.RedisConstants.*;

@Slf4j
@Service
public class CommentsServiceImpl implements CommentsService {

    @Resource
    private CommentsMapper commentsMapper;
    @Resource
    private PostMapper postMapper;
    @Resource
    private RedisTemplate redisTemplate;

    // ======================== 评论点赞 ========================

    @Transactional
    public Result toggleCommentLike(ToggleLikeDTO toggleLikeDTO) {
        Long userId = BaseContext.getCurrentId();
        toggleLikeDTO.setUserId(userId);
        Long commentId = toggleLikeDTO.getTargetId();
        String likeKey = LIKE_POST_KEY + "comment:" + commentId;
        String statsKey = COMMENT_STATS_KEY + commentId;

        if (Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())) {
            Long add = redisTemplate.opsForSet().add(likeKey, userId.toString());
            if (add == 1) {
                Long likeCount = redisTemplate.opsForHash().increment(statsKey, "likeCount", 1);
                postMapper.saveLikeInfo(toggleLikeDTO);
                postMapper.updateCommentLikeCount(1, commentId);

                ToggleLikeVO vo = ToggleLikeVO.builder().likeCount(likeCount).isLiked(true).build();
                return Result.success(vo);
            }
            if (add == 0) {
                Object likeCount = redisTemplate.opsForHash().get(statsKey, "likeCount");
                long count = likeCount == null ? 0 : Long.parseLong(likeCount.toString());
                return Result.success(ToggleLikeVO.builder().likeCount(count).isLiked(true).build());
            }
        } else {
            Long removed = redisTemplate.opsForSet().remove(likeKey, userId.toString());
            if (removed == 1) {
                Long likeCount = redisTemplate.opsForHash().increment(statsKey, "likeCount", -1);
                if (likeCount < 0) {
                    likeCount = 0L;
                    redisTemplate.opsForHash().put(statsKey, "likeCount", "0");
                }
                postMapper.deleteLikeInfo(toggleLikeDTO);
                postMapper.updateCommentLikeCount(-1, commentId);

                ToggleLikeVO vo = ToggleLikeVO.builder().likeCount(likeCount).isLiked(false).build();
                return Result.success(vo);
            }
            if (removed == 0) {
                Object likeCount = redisTemplate.opsForHash().get(statsKey, "likeCount");
                long count = likeCount == null ? 0 : Long.parseLong(likeCount.toString());
                return Result.success(ToggleLikeVO.builder().likeCount(count).isLiked(false).build());
            }
        }
        return Result.error("评论点赞/取消失败");
    }

    // ======================== 一级评论 / 回复评论 ========================

    @Transactional
    @Override
    public Result postComments(PostCommentsDTO postCommentsDTO) {
        Long userId = BaseContext.getCurrentId();
        Long statusId = postCommentsDTO.getStatusId();
        String content = postCommentsDTO.getContent();

        if (statusId == null) {
            return Result.error("微博 ID 不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            return Result.error("评论内容不能为空");
        }
        content = content.trim();

        Long postId = commentsMapper.getPostById(statusId);
        if (postId == null) {
            return Result.error("微博不存在");
        }

        Long parentId = postCommentsDTO.getParentId();
        Long replyUserId = postCommentsDTO.getReplyUserId();
        if (parentId != null) {
            Comment parentComment = commentsMapper.getCommentById(parentId);
            if (parentComment == null) {
                return Result.error("父评论不存在");
            }
            if (!parentComment.getStatusId().equals(statusId)) {
                return Result.error("父评论不属于该微博");
            }
            if (replyUserId == null) {
                replyUserId = parentComment.getUserId();
            }
        }

        Comment comment = Comment.builder()
                .statusId(statusId)
                .userId(userId)
                .parentId(parentId)
                .replyUserId(replyUserId)
                .content(content)
                .createdAt(LocalDateTime.now()).build();

        commentsMapper.saveComment(comment);

        commentsMapper.updateStatusCommentCount(statusId);

        String statsKey = POST_STATS_KEY + statusId;
        redisTemplate.opsForHash().increment(statsKey, "commentCount", 1);

        if (parentId != null) {
            redisTemplate.opsForHash().increment(COMMENT_STATS_KEY + parentId, "replyCount", 1);
        }

        Map<Object, Object> stats = redisTemplate.opsForHash().entries(statsKey);
        if (stats != null && !stats.isEmpty()) {
            long likeCount = parseStat(stats, "likeCount");
            long commentCount = parseStat(stats, "commentCount");
            long forwardCount = parseStat(stats, "forwardCount");
            long viewCount = parseStat(stats, "viewCount");
            long createTime = parseStat(stats, "createTime");
            if (createTime == 0) {
                createTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
            }

            double score = HotScoreCalculator.calculate(viewCount, likeCount, commentCount, forwardCount, createTime);
            redisTemplate.opsForZSet().add(HOT_POST_ZSET_KEY, statusId.toString(), score);
            redisTemplate.opsForSet().add(HOT_POST_CANDIDATE_KEY, statusId.toString());
        }

        deleteCommentListCache(statusId);
        if (parentId != null) {
            deleteReplyListCache(parentId);
        }

        CommentVO vo = buildCommentVO(comment, userId);
        return Result.success(vo);
    }

    @Override
    public Result getComments(CommentsResultDTO commentsResultDTO) {
        Long userId = BaseContext.getCurrentId();
        Long statusId = commentsResultDTO.getStatusId();

        if (statusId == null) {
            return Result.error("微博 ID 不能为空");
        }

        Integer page = commentsResultDTO.getPage() != null ? commentsResultDTO.getPage() : 1;
        Integer pageSize = commentsResultDTO.getPageSize() != null ? commentsResultDTO.getPageSize() : 10;

        String cacheKey = POST_COMMENT_LIST_KEY + statusId + ":" + page + ":" + pageSize;

        //1. 查缓存
        Object cacheJson = redisTemplate.opsForValue().get(cacheKey);
        if (cacheJson != null) {
            try {
                List<CommentVO> cacheList = JSONUtil.parseArray(cacheJson).toList(CommentVO.class);
                // 缓存命中时刷新动态数据（点赞数、点赞状态从 Redis 实时取）
                refreshDynamicData(cacheList, userId);
                Long total = getCommentTotal(statusId);
                return Result.success(new PageResult(total, cacheList));
            } catch (Exception e) {
                log.warn("评论缓存反序列化失败 key={}", cacheKey, e);
                redisTemplate.delete(cacheKey);
            }
        }

        //2. 分页查数据库（一级评论）
        int offset = (page - 1) * pageSize;
        List<Comment> topComments = commentsMapper.getTopComments(statusId, offset, pageSize);

        if (topComments.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, "[]", 60, TimeUnit.SECONDS);
            return Result.success(new PageResult(0L, Collections.emptyList()));
        }

        //3. 构建 VO
        List<CommentVO> voList = buildTopCommentVOs(topComments, userId);

        //4. 写入缓存，记录已缓存页
        redisTemplate.opsForValue().set(
                cacheKey,
                JSONUtil.toJsonStr(voList),
                300L + new Random().nextInt(300),
                TimeUnit.SECONDS
        );
        markPageCached(statusId, page, pageSize);

        //5. 返回
        Long total = getCommentTotal(statusId);
        return Result.success(new PageResult(total, voList));
    }

    @Override
    public Result getReplies(RepliesCommentsResultDTO repliesCommentsResultDTO) {
        Long userId = BaseContext.getCurrentId();
        Long commentId = repliesCommentsResultDTO.getCommentId();

        if (commentId == null) {
            return Result.error("评论 ID 不能为空");
        }

        Integer page = repliesCommentsResultDTO.getPage() != null ? repliesCommentsResultDTO.getPage() : 1;
        Integer pageSize = repliesCommentsResultDTO.getPageSize() != null ? repliesCommentsResultDTO.getPageSize() : 10;

        String cacheKey = COMMENT_REPLY_LIST_KEY + commentId + ":" + page + ":" + pageSize;

        //1. 查缓存
        Object cacheJson = redisTemplate.opsForValue().get(cacheKey);
        if (cacheJson != null) {
            try {
                List<CommentVO> cacheList = JSONUtil.parseArray(cacheJson).toList(CommentVO.class);
                // 缓存命中时刷新动态数据
                refreshDynamicData(cacheList, userId);
                Long total = getReplyTotal(commentId);
                return Result.success(new PageResult(total, cacheList));
            } catch (Exception e) {
                log.warn("回复缓存反序列化失败 key={}", cacheKey, e);
                redisTemplate.delete(cacheKey);
            }
        }

        //2. 分页查数据库
        int offset = (page - 1) * pageSize;
        List<Comment> replies = commentsMapper.getRepliesByCommentId(commentId, offset, pageSize);

        if (replies.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, "[]", 60, TimeUnit.SECONDS);
            return Result.success(new PageResult(0L, Collections.emptyList()));
        }

        //3. 批量查询用户信息
        List<CommentVO> voList = buildReplyVOs(replies, userId);

        //4. 写入缓存，记录已缓存页
        redisTemplate.opsForValue().set(
                cacheKey,
                JSONUtil.toJsonStr(voList),
                300L + new Random().nextInt(300),
                TimeUnit.SECONDS
        );
        markReplyPageCached(commentId, page, pageSize);

        //5. 返回
        Long total = getReplyTotal(commentId);
        return Result.success(new PageResult(total, voList));
    }

    // VO 组装
    private List<CommentVO> buildTopCommentVOs(List<Comment> topComments, Long userId) {
        List<Long> commentIds = topComments.stream().map(Comment::getId).toList();

        //1. 收集所有需要的用户 ID（一级评论者）
        Set<Long> userIdSet = topComments.stream()
                .map(Comment::getUserId)
                .collect(Collectors.toSet());

        //2. 查询回复数（从 Redis 批量取）
        Map<Long, Integer> replyCountMap = getReplyCounts(commentIds);

        //3. 查询前 3 条回复（SQL 层用 row_number 限制，避免全量拉取）
        List<Comment> top3Replies = commentsMapper.getRepliesByCommentIds(commentIds, 3);
        Map<Long, List<Comment>> repliesGroup = top3Replies.stream()
                .collect(Collectors.groupingBy(Comment::getParentId));

        //4. 收集回复中的用户 ID
        for (List<Comment> replyList : repliesGroup.values()) {
            for (Comment reply : replyList) {
                userIdSet.add(reply.getUserId());
                if (reply.getReplyUserId() != null) {
                    userIdSet.add(reply.getReplyUserId());
                }
            }
        }

        //5. 批量查用户
        Map<Long, UserVO> userMap = getUsers(new ArrayList<>(userIdSet));

        //6. 批量从 Redis 取点赞数
        Map<Long, Integer> likeCountMap = batchGetCommentLikeCounts(commentIds);

        //7. 组装
        List<CommentVO> voList = new ArrayList<>();
        for (Comment comment : topComments) {
            CommentVO vo = buildCommentVO(comment, userId);
            vo.setUser(userMap.get(comment.getUserId()));
            vo.setReplyCount(replyCountMap.getOrDefault(comment.getId(), 0));
            // 用 Redis 实时点赞数覆盖 DB 值
            Integer redisLikes = likeCountMap.get(comment.getId());
            if (redisLikes != null) {
                vo.setLikesCount(redisLikes);
            }

            List<Comment> replies = repliesGroup.get(comment.getId());
            if (replies != null && !replies.isEmpty()) {
                List<CommentVO> replyVOs = replies.stream()
                        .map(r -> {
                            CommentVO rvo = buildCommentVO(r, userId);
                            rvo.setUser(userMap.get(r.getUserId()));
                            if (r.getReplyUserId() != null) {
                                rvo.setReplyUser(userMap.get(r.getReplyUserId()));
                            }
                            // 回复的点赞数也从 Redis 覆盖
                            Integer rLikes = likeCountMap.get(r.getId());
                            if (rLikes != null) {
                                rvo.setLikesCount(rLikes);
                            }
                            return rvo;
                        })
                        .toList();
                vo.setReplies(replyVOs);
            } else {
                vo.setReplies(Collections.emptyList());
            }

            voList.add(vo);
        }
        return voList;
    }

    private List<CommentVO> buildReplyVOs(List<Comment> replies, Long currentUserId) {
        Set<Long> userIds = new HashSet<>();
        for (Comment r : replies) {
            userIds.add(r.getUserId());
            if (r.getReplyUserId() != null) {
                userIds.add(r.getReplyUserId());
            }
        }

        Map<Long, UserVO> userMap = getUsers(new ArrayList<>(userIds));

        // 批量取点赞数
        List<Long> replyIds = replies.stream().map(Comment::getId).toList();
        Map<Long, Integer> likeCountMap = batchGetCommentLikeCounts(replyIds);

        return replies.stream().map(r -> {
            CommentVO vo = buildCommentVO(r, currentUserId);
            vo.setUser(userMap.get(r.getUserId()));
            if (r.getReplyUserId() != null) {
                vo.setReplyUser(userMap.get(r.getReplyUserId()));
            }
            Integer redisLikes = likeCountMap.get(r.getId());
            if (redisLikes != null) {
                vo.setLikesCount(redisLikes);
            }
            return vo;
        }).toList();
    }


    private CommentVO buildCommentVO(Comment comment, Long currentUserId) {
        return CommentVO.builder()
                .id(comment.getId())
                .statusId(comment.getStatusId())
                .content(comment.getContent())
                .createdAt(comment.getCreatedAt())
                .likesCount(comment.getLikesCount() != null ? comment.getLikesCount() : 0)
                .isLiked(false)
                .parentId(comment.getParentId())
                .replyCount(0)
                .build();
    }

    private Map<Long, UserVO> getUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();

        List<Long> distinctIds = userIds.stream().distinct().toList();

        List<String> keys = distinctIds.stream()
                .map(uid -> USER_INFO_KEY + uid)
                .toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);

        Map<Long, UserVO> userMap = new HashMap<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < distinctIds.size(); i++) {
            String json = jsonList != null ? jsonList.get(i) : null;
            if (json != null && !json.isEmpty()) {
                if ("NULL".equals(json)) {
                    continue;
                }
                userMap.put(distinctIds.get(i), JSONUtil.toBean(json, UserVO.class));
            } else {
                missIds.add(distinctIds.get(i));
            }
        }

        if (!missIds.isEmpty()) {
            List<UserVO> dbUsers = commentsMapper.getUsersInfoByUserIds(missIds);
            Set<Long> foundIds = new HashSet<>();
            for (UserVO user : dbUsers) {
                userMap.put(user.getId(), user);
                foundIds.add(user.getId());
            }

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (UserVO user : dbUsers) {
                    String key = USER_INFO_KEY + user.getId();
                    String value = JSONUtil.toJsonStr(user);
                    long expire = USER_INFO_TTL + new Random().nextInt(3600);
                    connection.stringCommands().set(
                            key.getBytes(),
                            value.getBytes(),
                            Expiration.seconds(expire),
                            RedisStringCommands.SetOption.UPSERT
                    );
                }
                for (Long missId : missIds) {
                    if (!foundIds.contains(missId)) {
                        String key = USER_INFO_KEY + missId;
                        long expire = 300 + new Random().nextInt(300);
                        connection.stringCommands().set(
                                key.getBytes(),
                                "NULL".getBytes(),
                                Expiration.seconds(expire),
                                RedisStringCommands.SetOption.UPSERT
                        );
                    }
                }
                return null;
            });
        }

        return userMap;
    }

    private long parseStat(Map<Object, Object> stats, String field) {
        Object obj = stats.get(field);
        if (obj == null) return 0;
        return Long.parseLong(obj.toString());
    }

    // ======================== replyCount（Redis） ========================

    private Map<Long, Integer> getReplyCounts(List<Long> commentIds) {
        if (commentIds.isEmpty()) return Collections.emptyMap();

        List<String> keys = commentIds.stream()
                .map(id -> COMMENT_STATS_KEY + id)
                .toList();
        List<Object> vals = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.hashCommands().hGet(key.getBytes(), "replyCount".getBytes());
            }
            return null;
        });

        Map<Long, Integer> result = new HashMap<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < commentIds.size(); i++) {
            Object val = vals.get(i);
            if (val != null) {
                result.put(commentIds.get(i), Integer.parseInt(val.toString()));
            } else {
                missIds.add(commentIds.get(i));
            }
        }

        if (!missIds.isEmpty()) {
            var dbCounts = commentsMapper.countRepliesByCommentIds(missIds);
            for (var rc : dbCounts) {
                result.put(rc.getCommentId(), rc.getCount());
                redisTemplate.opsForHash().put(COMMENT_STATS_KEY + rc.getCommentId(), "replyCount", rc.getCount().toString());
            }
            for (Long missId : missIds) {
                if (!result.containsKey(missId)) {
                    result.put(missId, 0);
                    redisTemplate.opsForHash().put(COMMENT_STATS_KEY + missId, "replyCount", "0");
                }
            }
        }

        return result;
    }

    // ======================== likeCount 批量获取 ========================

    private Map<Long, Integer> batchGetCommentLikeCounts(List<Long> commentIds) {
        if (commentIds.isEmpty()) return Collections.emptyMap();

        // 用 RedisTemplate 的 opsForHash 逐个读取，走配置的序列化器
        // 因为 Hash value 用了 GenericJackson2JsonRedisSerializer，pipeline 原生连接
        // 返回的是原始字节无法直接 parseInt，这里用模板方法保证反序列化正确
        Map<Long, Integer> result = new HashMap<>();
        for (Long id : commentIds) {
            Object val = redisTemplate.opsForHash().get(COMMENT_STATS_KEY + id, "likeCount");
            if (val != null) {
                result.put(id, Integer.parseInt(val.toString()));
            }
        }
        return result;
    }

    // ======================== total ========================

    private Long getCommentTotal(Long statusId) {
        Object obj = redisTemplate.opsForHash().get(POST_STATS_KEY + statusId, "commentCount");
        if (obj == null) {
            return commentsMapper.countTotalByStatusId(statusId);
        }
        return Long.parseLong(obj.toString());
    }

    private Long getReplyTotal(Long commentId) {
        Object obj = redisTemplate.opsForHash().get(COMMENT_STATS_KEY + commentId, "replyCount");
        if (obj != null) {
            return Long.parseLong(obj.toString());
        }
        return commentsMapper.countRepliesByCommentId(commentId);
    }

    /**
     * 缓存命中后刷新动态数据：点赞数从 Redis 实时取，点赞状态从 Redis 实时查
     */
    private void refreshDynamicData(List<CommentVO> list, Long userId) {
        if (list == null || list.isEmpty()) return;

        // 收集所有需要刷新的 commentId（含嵌套 replies）
        List<Long> allIds = new ArrayList<>();
        for (CommentVO vo : list) {
            allIds.add(vo.getId());
            if (vo.getReplies() != null) {
                for (CommentVO reply : vo.getReplies()) {
                    allIds.add(reply.getId());
                }
            }
        }

        // 批量取 likeCount
        Map<Long, Integer> likeCountMap = batchGetCommentLikeCounts(allIds);

        // 批量取 isLiked
        Map<Long, Boolean> isLikedMap = new HashMap<>();
        if (userId != null) {
            List<String> likeKeys = allIds.stream()
                    .map(id -> LIKE_POST_KEY + "comment:" + id)
                    .toList();
            List<Object> likeVals = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : likeKeys) {
                    connection.setCommands().sIsMember(key.getBytes(), userId.toString().getBytes());
                }
                return null;
            });
            for (int i = 0; i < allIds.size(); i++) {
                Boolean member = (Boolean) likeVals.get(i);
                isLikedMap.put(allIds.get(i), member != null && member);
            }
        }

        // 回填
        for (CommentVO vo : list) {
            refreshVO(vo, likeCountMap, isLikedMap);
            if (vo.getReplies() != null) {
                for (CommentVO reply : vo.getReplies()) {
                    refreshVO(reply, likeCountMap, isLikedMap);
                }
            }
        }
    }

    private void refreshVO(CommentVO vo, Map<Long, Integer> likeCountMap, Map<Long, Boolean> isLikedMap) {
        Integer count = likeCountMap.get(vo.getId());
        if (count != null) {
            vo.setLikesCount(count);
        }
        Boolean liked = isLikedMap.get(vo.getId());
        if (liked != null) {
            vo.setIsLiked(liked);
        }
    }

    // 缓存失效
    private void deleteCommentListCache(Long statusId) {
        String pagesKey = POST_COMMENT_LIST_KEY + statusId + ":pages";
        deleteAllCachedPages(pagesKey, POST_COMMENT_LIST_KEY + statusId);
    }

    private void deleteReplyListCache(Long commentId) {
        String pagesKey = COMMENT_REPLY_LIST_KEY + commentId + ":pages";
        deleteAllCachedPages(pagesKey, COMMENT_REPLY_LIST_KEY + commentId);
    }

    private void deleteAllCachedPages(String pagesKey, String keyPrefix) {
        Set<Object> pages = redisTemplate.opsForSet().members(pagesKey);
        if (pages != null && !pages.isEmpty()) {
            for (Object p : pages) {
                redisTemplate.delete(keyPrefix + ":" + p);
            }
            redisTemplate.delete(pagesKey);
        }
        redisTemplate.delete(keyPrefix + ":1:10");
    }

    // 页追踪
    private void markPageCached(Long statusId, Integer page, Integer pageSize) {
        String pagesKey = POST_COMMENT_LIST_KEY + statusId + ":pages";
        redisTemplate.opsForSet().add(pagesKey, page + ":" + pageSize);
        redisTemplate.expire(pagesKey, 1800, TimeUnit.SECONDS);
    }

    private void markReplyPageCached(Long commentId, Integer page, Integer pageSize) {
        String pagesKey = COMMENT_REPLY_LIST_KEY + commentId + ":pages";
        redisTemplate.opsForSet().add(pagesKey, page + ":" + pageSize);
        redisTemplate.expire(pagesKey, 1800, TimeUnit.SECONDS);
    }
}
