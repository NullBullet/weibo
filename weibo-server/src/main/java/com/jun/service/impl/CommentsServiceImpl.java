package com.jun.service.impl;

import cn.hutool.json.JSONUtil;
import com.jun.config.RabbitMQConfig;
import com.jun.context.BaseContext;
import com.jun.dto.CommentsResultDTO;
import com.jun.dto.PostCommentsDTO;
import com.jun.dto.RepliesCommentsResultDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.entity.Comment;
import com.jun.enumeration.TargetType;
import com.jun.mapper.CommentsMapper;
import com.jun.mapper.PostMapper;
import com.jun.mq.message.CommentMessage;
import com.jun.mq.message.LikeMessage;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.CommentsService;
import com.jun.utils.*;
import com.jun.vo.CommentVO;
import com.jun.vo.ToggleLikeVO;
import com.jun.vo.UserVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.jun.config.RabbitMQConfig.*;
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
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private PostStatsHelper postStatsHelper;
    @Resource
    private UserStatsHelper userStatsHelper;
    @Resource
    private CommentStatsHelper commentStatsHelper;
    @Resource
    private LikeSetHelper likeSetHelper;

    // ======================== 评论点赞 ========================

    @Transactional
    public Result toggleCommentLike(ToggleLikeDTO toggleLikeDTO) {
        Long userId = BaseContext.getCurrentId();
        toggleLikeDTO.setUserId(userId);
        Long commentId = toggleLikeDTO.getTargetId();
        String likeKey = LIKE_COMMENT_KEY + commentId;
        String statsKey = COMMENT_STATS_KEY + commentId;

        // 获取评论作者，用于维护作者的获赞数
        Comment comment = commentsMapper.getCommentById(commentId);
        if (comment == null || comment.getIsDeleted() == 1) {
            return Result.error("评论不存在");
        }
        Long commentOwnerId = comment.getUserId();

        // 防御：作者获赞数缓存缺失时先重建；必须在下方 updateStatistics（DB 写）之前，
        // 否则重建会读到未提交计数导致双重加，也避免 HINCRBY 创建残缺 hash
        userStatsHelper.ensureStats(commentOwnerId);

        // 防御：点赞集合缓存缺失时先从 attitudes 表重建，保证 SADD/SREM 返回值反映
        // 数据库真实状态（防止重复点赞重复计数/落库、取消点赞误判为幂等）
        likeSetHelper.ensureLikeSet(toggleLikeDTO.getTargetType(), commentId);

        if (Boolean.TRUE.equals(toggleLikeDTO.getIsLiked())) {
            Long add = redisTemplate.opsForSet().add(likeKey, userId.toString());
            if (add == 1) {
                // 防御 + 增量 + 续期：缓存缺失/残缺时先完整重建，避免 HINCRBY 建立在错误基线上
                Long likeCount = commentStatsHelper.incrementLike(commentId, 1);
                // 点赞写入成功，续期点赞集合 TTL
                likeSetHelper.refreshTtl(toggleLikeDTO.getTargetType(), commentId);

                // MQ 异步落库 + 通知：comment.like.queue 与 notice.like.queue 共同消费
                LikeMessage message = LikeMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .userId(userId)
                        .targetId(commentId)
                        .targetType(TargetType.COMMENT)
                        .isLiked(true)
                        .retryCount(0)
                        .build();
                CorrelationData correlationData = new CorrelationData(message.getMessageId());
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                        COMMENT_LIKE,
                        message,
                        correlationData
                );

                // 评论作者的获赞数 +1（DB + Redis）
                if (commentOwnerId != null) {
                    postMapper.updateStatistics(1, commentOwnerId);
                    redisTemplate.opsForHash().increment(USER_STATS_KEY + commentOwnerId, "likesCount", 1);
                    // 统计值修改成功，续期缓存 TTL
                    userStatsHelper.refreshTtl(commentOwnerId);
                }

                ToggleLikeVO vo = ToggleLikeVO.builder().likeCount(likeCount).isLiked(true).build();
                return Result.success(vo);
            }
            if (add == 0) {
                // 已点赞的幂等分支：miss 时从 DB 重建兜底，避免误报 0
                Long count = commentStatsHelper.getLikeCount(commentId);
                long likeCount = count == null ? 0 : count;
                return Result.success(ToggleLikeVO.builder().likeCount(likeCount).isLiked(true).build());
            }
        } else {
            Long removed = redisTemplate.opsForSet().remove(likeKey, userId.toString());
            if (removed == 1) {
                Long likeCount = commentStatsHelper.incrementLike(commentId, -1);
                if (likeCount < 0) {
                    likeCount = 0L;
                    redisTemplate.opsForHash().put(statsKey, "likeCount", 0);
                    commentStatsHelper.refreshTtl(commentId);
                }
                // 取消点赞写入成功，续期点赞集合 TTL
                likeSetHelper.refreshTtl(toggleLikeDTO.getTargetType(), commentId);

                // MQ 异步落库 + 通知：comment.like.queue 与 notice.like.queue 共同消费
                LikeMessage message = LikeMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .userId(userId)
                        .targetId(commentId)
                        .targetType(TargetType.COMMENT)
                        .isLiked(false)
                        .retryCount(0)
                        .build();
                CorrelationData correlationData = new CorrelationData(message.getMessageId());
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                        COMMENT_LIKE_CANCEL,
                        message,
                        correlationData
                );

                // 评论作者的获赞数 -1（DB + Redis）
                if (commentOwnerId != null) {
                    postMapper.updateStatistics(-1, commentOwnerId);
                    redisTemplate.opsForHash().increment(USER_STATS_KEY + commentOwnerId, "likesCount", -1);
                    // 统计值修改成功，续期缓存 TTL
                    userStatsHelper.refreshTtl(commentOwnerId);
                }

                ToggleLikeVO vo = ToggleLikeVO.builder().likeCount(likeCount).isLiked(false).build();
                return Result.success(vo);
            }
            if (removed == 0) {
                // 未点赞的幂等分支：miss 时从 DB 重建兜底，避免误报 0
                Long count = commentStatsHelper.getLikeCount(commentId);
                long likeCount = count == null ? 0 : count;
                return Result.success(ToggleLikeVO.builder().likeCount(likeCount).isLiked(false).build());
            }
        }
        return Result.error("评论点赞/取消失败");
    }

    // ======================== 删除评论 ========================

    @Transactional
    @Override
    public Result deleteComment(Long commentId) {
        Long userId = BaseContext.getCurrentId();

        //1. 校验评论是否存在
        Comment comment = commentsMapper.getCommentById(commentId);
        if (comment == null || comment.getIsDeleted() == 1) {
            return Result.error("评论不存在");
        }

        //2. 校验是否是自己的评论
        if (!comment.getUserId().equals(userId)) {
            return Result.error("只能删除自己的评论");
        }

        Long statusId = comment.getStatusId();

        //3.1 防御：删除回复时父评论统计缓存缺失先重建（必须在 DB 软删之前——
        // 否则重建读到的回复数已不含本回复，基线少 1，再递减会双重扣减），
        // 与上方 postStatsHelper.ensureStats 防御模式一致
        if (comment.getParentId() != null) {
            commentStatsHelper.ensureStats(comment.getParentId());
        }

        //3.2 删除顶级评论时，先取未删除回复的 id 列表（软删后查询过滤 is_deleted 将拿不到）
        List<Long> replyIds = null;
        if (comment.getParentId() == null) {
            replyIds = commentsMapper.getNonDeletedReplyIds(commentId);
        }

        int deleteCount = 1;
        if (comment.getParentId() == null) {
            int replyCount = commentsMapper.countNonDeletedReplies(commentId);
            deleteCount += replyCount;
        }

        //4. 软删除评论本身
        commentsMapper.softDeleteComment(commentId);

        //5. 如果是顶级评论，一并软删除其下所有回复
        if (comment.getParentId() == null) {
            commentsMapper.softDeleteRepliesByParentId(commentId);
        }

        //6. 防御：缓存缺失时先重建（必须在 DB 扣减之前——
        // 否则同事务内 selectStatsByIds 能读到未提交的扣减后值，重建基线错误导致双重扣减）
        postStatsHelper.ensureStats(statusId);

        //7. DB: statuses.comments_count 扣减
        commentsMapper.decreaseStatusCommentCount(statusId, deleteCount);

        //8. 清理被删评论自身的统计缓存与点赞集合（缓存失效保持在提交前，回滚只会导致 miss 重建）
        redisTemplate.delete(COMMENT_STATS_KEY + commentId);
        redisTemplate.delete(LIKE_COMMENT_KEY + commentId);
        if (replyIds != null && !replyIds.isEmpty()) {
            for (Long replyId : replyIds) {
                redisTemplate.delete(COMMENT_STATS_KEY + replyId);
                redisTemplate.delete(LIKE_COMMENT_KEY + replyId);
            }
        }

        //9. 删除评论列表缓存
        deleteCommentListCache(statusId);
        if (comment.getParentId() != null) {
            deleteReplyListCache(comment.getParentId());
        }

        //10. Redis 计数扣减延迟到事务提交后：
        // ensureStats 已在 DB 变更前建立基线，提交后只做原子扣减；
        // 若缓存缺失，Helper 会从 DB 重建（已包含本次删除），不再重复扣减
        final Long parentId = comment.getParentId();
        final int removedCount = deleteCount;
        runAfterCommit(() -> {
            postStatsHelper.incrementCommentCountAfterCommit(statusId, -removedCount);
            if (parentId != null) {
                commentStatsHelper.incrementReplyAfterCommit(parentId, -1);
            }
        });

        return Result.success();
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
            if (parentComment == null || parentComment.getIsDeleted() == 1) {
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

        // 父评论回复数基线必须在插入回复之前建立：
        // 若在插入后重建，DB 查询会把本条未提交回复算进基线，提交后再 +1 会重复计数
        if (parentId != null) {
            commentStatsHelper.ensureStats(parentId);
        }

        // DB：同步插入评论，获取自增 ID（后续 MQ 通知需要 commentId）
        commentsMapper.saveComment(comment);

        // 缓存失效放在提交前：回滚只会导致缓存 miss，读路径可按 DB 重建；
        // 若放到提交后，会出现提交后仍读到旧列表的窗口
        deleteCommentListCache(statusId);
        if (parentId != null) {
            deleteReplyListCache(parentId);
        }

        // MQ 消息提前构建，实际发送延迟到事务提交后
        CommentMessage message = CommentMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .userId(userId)
                .statusId(statusId)
                .parentId(parentId)
                .replyUserId(replyUserId)
                .commentId(comment.getId())
                .content(content)
                .retryCount(0)
                .build();

        CorrelationData correlationData = new CorrelationData(message.getMessageId());

        // 事务提交后统一维护 Redis 并发送 MQ：
        // 1. 回滚时不会留下评论数/回复数/热榜漂移；
        // 2. 先写 Redis 再发 MQ，避免 Consumer 在 Redis 增量前更新 DB comments_count
        runAfterCommit(() -> {
            String statsKey = POST_STATS_KEY + statusId;
            try {
                postStatsHelper.ensureStats(statusId);
                redisTemplate.opsForHash().increment(statsKey, "commentCount", 1);

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
            } catch (Exception e) {
                log.error("评论提交后维护帖子统计/热榜失败: statusId={}, commentId={}",
                        statusId, comment.getId(), e);
            }

            if (parentId != null) {
                commentStatsHelper.incrementReplyAfterCommit(parentId, 1);
            }

            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                        COMMENT_CREATE,
                        message,
                        correlationData
                );
            } catch (Exception e) {
                log.error("评论消息发送失败: statusId={}, commentId={}, messageId={}",
                        statusId, comment.getId(), message.getMessageId(), e);
            }
        });

        CommentVO vo = buildCommentVO(comment, userId);
        return Result.success(vo);
    }

    /**
     * 数据库事务提交后执行 Redis 维护和 MQ 发送。若当前没有事务同步（例如非代理调用），
     * 则直接执行，保持降级行为。
     */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
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
            // 空值缓存 TTL 很短（10s），避免新增评论后长时间不可见
            redisTemplate.opsForValue().set(cacheKey, "[]", 10, TimeUnit.SECONDS);
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
            redisTemplate.opsForValue().set(cacheKey, "[]", 10, TimeUnit.SECONDS);
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

        //6. 收集所有需要查点赞的 ID（一级评论 + 嵌套回复）
        List<Long> allCommentIds = new ArrayList<>(commentIds);
        for (List<Comment> replyList : repliesGroup.values()) {
            for (Comment reply : replyList) {
                allCommentIds.add(reply.getId());
            }
        }

        //6.1 批量从 Redis 取点赞数 + isLiked
        Map<Long, Integer> likeCountMap = batchGetCommentLikeCounts(allCommentIds);
        Map<Long, Boolean> isLikedMap = batchGetCommentIsLiked(allCommentIds, userId);

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
            // 用 Redis 实时 isLiked 覆盖
            Boolean liked = isLikedMap.get(comment.getId());
            if (liked != null) {
                vo.setIsLiked(liked);
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
                            Integer rLikes = likeCountMap.get(r.getId());
                            if (rLikes != null) {
                                rvo.setLikesCount(rLikes);
                            }
                            Boolean rLiked = isLikedMap.get(r.getId());
                            if (rLiked != null) {
                                rvo.setIsLiked(rLiked);
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

        // 批量取点赞数 + isLiked
        List<Long> replyIds = replies.stream().map(Comment::getId).toList();
        Map<Long, Integer> likeCountMap = batchGetCommentLikeCounts(replyIds);
        Map<Long, Boolean> isLikedMap = batchGetCommentIsLiked(replyIds, currentUserId);

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
            Boolean liked = isLikedMap.get(r.getId());
            if (liked != null) {
                vo.setIsLiked(liked);
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
        List<String> hitKeys = new ArrayList<>();

        for (int i = 0; i < commentIds.size(); i++) {
            Object val = vals.get(i);
            if (val != null) {
                result.put(commentIds.get(i), Integer.parseInt(val.toString()));
                hitKeys.add(keys.get(i));
            } else {
                missIds.add(commentIds.get(i));
            }
        }

        // 命中续期（pipeline 批量），活跃评论的统计缓存不过期
        if (!hitKeys.isEmpty()) {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : hitKeys) {
                    connection.keyCommands().expire(key.getBytes(), COMMENT_STATS_TTL);
                }
                return null;
            });
        }

        if (!missIds.isEmpty()) {
            // miss 兜底：一次查出回复数 + 点赞数，完整回填两字段（putIfAbsent 防覆盖并发增量）
            var dbStats = commentsMapper.getCommentStatsByIds(missIds);
            for (var vo : dbStats) {
                result.put(vo.getCommentId(), vo.getCount() == null ? 0 : vo.getCount());
                commentStatsHelper.fillStats(vo.getCommentId(), vo.getCount(), vo.getLikesCount());
            }
            // DB 也不存在（评论已删除）的 missId：回填零值，保持原有兜底行为
            for (Long missId : missIds) {
                if (!result.containsKey(missId)) {
                    result.put(missId, 0);
                    commentStatsHelper.fillStats(missId, 0, 0);
                }
            }
            log.info("批量回填评论统计缓存: count={}", missIds.size());
        }

        return result;
    }

    // ======================== likeCount / isLiked 批量获取 ========================

    private Map<Long, Integer> batchGetCommentLikeCounts(List<Long> commentIds) {
        if (commentIds.isEmpty()) return Collections.emptyMap();

        List<String> keys = commentIds.stream()
                .map(id -> COMMENT_STATS_KEY + id)
                .toList();
        List<Object> vals = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.hashCommands().hGet(key.getBytes(), "likeCount".getBytes());
            }
            return null;
        });

        Map<Long, Integer> result = new HashMap<>();
        List<Long> missIds = new ArrayList<>();
        List<String> hitKeys = new ArrayList<>();

        for (int i = 0; i < commentIds.size(); i++) {
            Object val = vals.get(i);
            if (val != null) {
                result.put(commentIds.get(i), Integer.parseInt(val.toString()));
                hitKeys.add(keys.get(i));
            } else {
                missIds.add(commentIds.get(i));
            }
        }

        // 命中续期（pipeline 批量），活跃评论的统计缓存不过期
        if (!hitKeys.isEmpty()) {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : hitKeys) {
                    connection.keyCommands().expire(key.getBytes(), COMMENT_STATS_TTL);
                }
                return null;
            });
        }

        // miss 兜底：原来直接不回填（前端显示旧值），改为查 DB 并完整回填两字段
        if (!missIds.isEmpty()) {
            var dbStats = commentsMapper.getCommentStatsByIds(missIds);
            for (var vo : dbStats) {
                result.put(vo.getCommentId(), vo.getLikesCount() == null ? 0 : vo.getLikesCount());
                commentStatsHelper.fillStats(vo.getCommentId(), vo.getCount(), vo.getLikesCount());
            }
            for (Long missId : missIds) {
                if (!result.containsKey(missId)) {
                    result.put(missId, 0);
                    commentStatsHelper.fillStats(missId, 0, 0);
                }
            }
            log.info("批量回填评论点赞数缓存: count={}", missIds.size());
        }

        return result;
    }

    private Map<Long, Boolean> batchGetCommentIsLiked(List<Long> commentIds, Long userId) {
        if (commentIds.isEmpty() || userId == null) return Collections.emptyMap();

        // 兜底：点赞集合缓存缺失时从数据库重建，防止已点赞显示为未点赞
        likeSetHelper.ensureLikeSets(TargetType.COMMENT, commentIds);

        // pipeline 批量 SISMEMBER，避免逐条 isMember 的 N 次网络往返
        byte[] member = userId.toString().getBytes();
        List<Object> members = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long id : commentIds) {
                connection.setCommands().sIsMember((LIKE_COMMENT_KEY + id).getBytes(), member);
            }
            return null;
        });

        Map<Long, Boolean> result = new HashMap<>();
        for (int i = 0; i < commentIds.size(); i++) {
            result.put(commentIds.get(i), Boolean.TRUE.equals(members.get(i)));
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
        // miss 时完整重建（含 likeCount），重建失败再直查 DB 兜底
        Long count = commentStatsHelper.getReplyCount(commentId);
        if (count != null) {
            return count;
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
        Map<Long, Boolean> isLikedMap = batchGetCommentIsLiked(allIds, userId);

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
        // 兜底：删除常见的首页 key（空值缓存和默认分页）
        deleteCommonPageKeys(POST_COMMENT_LIST_KEY + statusId);
    }

    private void deleteReplyListCache(Long commentId) {
        String pagesKey = COMMENT_REPLY_LIST_KEY + commentId + ":pages";
        deleteAllCachedPages(pagesKey, COMMENT_REPLY_LIST_KEY + commentId);
        deleteCommonPageKeys(COMMENT_REPLY_LIST_KEY + commentId);
    }

    private void deleteCommonPageKeys(String keyPrefix) {
        for (String suffix : COMMON_PAGE_KEY_SUFFIXES) {
            redisTemplate.delete(keyPrefix + suffix);
        }
    }

    private void deleteAllCachedPages(String pagesKey, String keyPrefix) {
        Set<Object> pages = redisTemplate.opsForSet().members(pagesKey);
        if (pages != null && !pages.isEmpty()) {
            for (Object p : pages) {
                redisTemplate.delete(keyPrefix + ":" + p);
            }
            redisTemplate.delete(pagesKey);
        }
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
