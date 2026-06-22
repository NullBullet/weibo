package com.jun;

import com.jun.entity.Comment;
import com.jun.entity.Statuses;
import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import com.jun.dto.ToggleLikeDTO;
import com.jun.mapper.AttitudeAggregate;
import com.jun.mapper.AuthMapper;
import com.jun.mapper.FollowMapper;
import com.jun.mapper.PostMapper;
import com.jun.mapper.TestMapper;
import com.jun.mapper.UsersMapper;
import com.jun.mq.message.FeedMessage;
import com.jun.utils.HotScoreCalculator;
import com.jun.vo.SaveStatusVO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.jun.constant.RedisConstants.*;

@SpringBootTest
public class WeiboServerApplicationTests {

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private TestMapper testMapper;
    @Resource
    private AuthMapper authMapper;
    @Resource
    private UsersMapper usersMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private FollowMapper followMapper;
    @Resource
    private PostMapper postMapper;

    private static final String DEFAULT_AVATAR = "https://java-ai-kurisutina.oss-cn-beijing.aliyuncs.com/weibo/avatars/default.jpg";

    // ======================== 原有初始化方法 ========================

    //初始化redis所有用户发的微博id统计
    void createUserPostIds() {

        // 1. 查所有用户ID
        Set<Long> userIds = testMapper.getUserId();

        for (Long userId : userIds) {

            // 2. 查该用户的所有帖子
            List<Statuses> statuses = testMapper.getStatusByUserId(userId);

            String key = USER_POST_IDS_KEY + userId;

            // 3. 批量写入 ZSet（推荐用 pipeline）
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

                for (Statuses status : statuses) {
                    Long postId = status.getId();

                    // 用发布时间做 score（核心！）
                    long score = status.getCreatedAt().toEpochSecond(ZoneOffset.UTC);

                    connection.zAdd(
                            key.getBytes(),
                            score,
                            postId.toString().getBytes()
                    );
                }
                return null;
            });
            System.out.println("用户 " + userId + " 的帖子ZSet构建完成");
        }
    }

    void initPostStatsToRedis() {
        System.out.println(">>> 开始从数据库加载微博数据并同步到 Redis...");

        List<Statuses> statusesList = testMapper.selectList(null);
        if (statusesList == null || statusesList.isEmpty()) {
            System.out.println("数据库中没有微博数据。");
            return;
        }

        var keySerializer = redisTemplate.getKeySerializer();
        var hashKeySerializer = redisTemplate.getHashKeySerializer();
        StringRedisSerializer stringValueSerializer = new StringRedisSerializer();

        System.out.println("开始写入 Redis，共 " + statusesList.size() + " 条数据...");

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Statuses status : statusesList) {
                byte[] rawKey = keySerializer.serialize(POST_STATS_KEY + status.getId());
                long createTimeStamp = status.getCreatedAt().toEpochSecond(ZoneOffset.UTC);

                Map<String, Object> hashData = new HashMap<>();
                hashData.put("likeCount", status.getAttitudesCount() != null ? status.getAttitudesCount() : 0);
                hashData.put("commentCount", status.getCommentsCount() != null ? status.getCommentsCount() : 0);
                hashData.put("forwardCount", status.getRepostsCount() != null ? status.getRepostsCount() : 0);
                hashData.put("viewCount", status.getViewsCount() != null ? status.getViewsCount() : 0);
                hashData.put("createTime", createTimeStamp);

                for (Map.Entry<String, Object> entry : hashData.entrySet()) {
                    byte[] field = hashKeySerializer.serialize(entry.getKey());
                    byte[] value = stringValueSerializer.serialize(String.valueOf(entry.getValue()));
                    connection.hSet(rawKey, field, value);
                }
            }
            return null;
        });

        System.out.println(">>> 同步完成！共处理 " + statusesList.size() + " 条微博统计信息。");
    }

    void initUserStatsTable() {
        System.out.println(">>> 开始初始化用户统计表...");

        List<User> users = testMapper.selectListusers(null);
        if (users == null || users.isEmpty()) {
            System.out.println("用户表为空，无需初始化。");
            return;
        }

        List<UserStatistics> statsListToSave = new ArrayList<>();
        for (User user : users) {
            UserStatistics stats = new UserStatistics();
            stats.setUserId(user.getId());
            stats.setFollowingCount(0);
            stats.setFollowersCount(0);
            stats.setStatusesCount(0);
            stats.setLikesCount(0);
            stats.setUpdatedAt(LocalDateTime.now());
            statsListToSave.add(stats);
        }

        boolean result = testMapper.insertBatchSomeColumn(statsListToSave);
        if (result) {
            System.out.println(">>> 初始化成功！共处理 " + statsListToSave.size() + " 条记录。");
        } else {
            System.out.println(">>> 初始化失败或无数据变更。");
        }
    }

    void syncFollowCountsToDb() {
        System.out.println(">>> 开始从 Redis 同步关注/粉丝计数到 user_statistics...");

        List<User> users = testMapper.selectListusers(null);
        if (users == null || users.isEmpty()) {
            System.out.println("用户表为空。");
            return;
        }

        int updated = 0;
        for (User user : users) {
            Long userId = user.getId();
            String statsKey = USER_STATS_KEY + userId;

            Object followingObj = redisTemplate.opsForHash().get(statsKey, "followingCount");
            int followingCount = 0;
            if (followingObj != null) {
                followingCount = Integer.parseInt(followingObj.toString());
            }

            Object fansObj = redisTemplate.opsForHash().get(statsKey, "followersCount");
            int followersCount = 0;
            if (fansObj != null) {
                followersCount = Integer.parseInt(fansObj.toString());
            }

            if (followingCount > 0 || followersCount > 0) {
                testMapper.updateUserFollowCounts(userId, followingCount, followersCount);
                updated++;
                System.out.println("用户 " + userId + ": following=" + followingCount + ", followers=" + followersCount);
            }
        }

        System.out.println(">>> 同步完成！共更新 " + updated + " 条记录。");
    }

    void initFanInboxes() {
        System.out.println(">>> 开始初始化粉丝收件箱...");

        Set<Long> userIds = testMapper.getUserId();
        if (userIds == null || userIds.isEmpty()) {
            System.out.println("没有用户数据，跳过。");
            return;
        }

        int totalFanPushes = 0;

        for (Long userId : userIds) {
            List<Statuses> posts = testMapper.getStatusByUserId(userId);
            if (posts == null || posts.isEmpty()) continue;

            Set<String> fans = redisTemplate.opsForSet().members(FOLLOW_FANS_KEY + userId);
            if (fans == null || fans.isEmpty()) continue;

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String fanId : fans) {
                    byte[] feedKey = (FEED_USER_KEY + fanId).getBytes();
                    for (Statuses post : posts) {
                        double score = post.getCreatedAt().toEpochSecond(ZoneOffset.UTC);
                        connection.zAdd(feedKey, score, post.getId().toString().getBytes());
                    }
                }
                return null;
            });

            totalFanPushes += fans.size();
            System.out.println("用户 " + userId + " 的 " + posts.size() + " 条帖子 -> " + fans.size() + " 个粉丝收件箱");
        }

        System.out.println(">>> 粉丝收件箱初始化完成！共影响 " + totalFanPushes + " 个粉丝收件箱");
    }

    void initNotificationLike() {
        System.out.println(">>> 开始初始化 notification_like 表...");

        List<AttitudeAggregate> aggregates = testMapper.getAttitudeAggregates();
        if (aggregates == null || aggregates.isEmpty()) {
            System.out.println("点赞表无数据，跳过。");
            return;
        }

        int inserted = 0;
        for (AttitudeAggregate agg : aggregates) {
            if (agg.getTargetType() == null || agg.getTargetId() == null) continue;

            Long receiverId;
            if (agg.getTargetType() == 1) {
                receiverId = testMapper.getPostOwnerId(agg.getTargetId());
            } else if (agg.getTargetType() == 2) {
                receiverId = testMapper.getCommentOwnerId(agg.getTargetId());
            } else {
                continue;
            }
            if (receiverId == null) continue;

            testMapper.upsertNotificationLike(receiverId, agg.getTargetType(), agg.getTargetId(),
                    agg.getLikeCount(), agg.getLastLikeTime());
            inserted++;

            System.out.println("通知 " + inserted + ": receiver=" + receiverId
                    + ", type=" + (agg.getTargetType() == 1 ? "帖子" : "评论")
                    + ", target=" + agg.getTargetId() + ", likes=" + agg.getLikeCount());
        }

        System.out.println(">>> notification_like 初始化完成！共写入 " + inserted + " 条记录");
    }

    void initNotificationComment() {
        System.out.println(">>> 开始初始化 notification_comment 表...");

        List<Comment> comments = testMapper.getAllComments();
        if (comments == null || comments.isEmpty()) {
            System.out.println("评论表无数据，跳过。");
            return;
        }

        int inserted = 0;
        for (Comment comment : comments) {
            Long senderId = comment.getUserId();
            Long commentId = comment.getId();
            Long parentId = comment.getParentId();
            Long replyUserId = comment.getReplyUserId();
            LocalDateTime createTime = comment.getCreatedAt();

            Long receiverId;
            Integer targetType;
            Long targetId;

            if (parentId == null) {
                receiverId = testMapper.getPostOwnerId(comment.getStatusId());
                targetType = 1;
                targetId = comment.getStatusId();
            } else {
                if (replyUserId == null) {
                    receiverId = testMapper.getCommentOwnerId(parentId);
                } else {
                    receiverId = replyUserId;
                }
                targetType = 2;
                targetId = parentId;
            }

            if (receiverId == null || receiverId.equals(senderId)) continue;

            testMapper.insertCommentNotification(receiverId, senderId, targetType, targetId, commentId, createTime);
            inserted++;

            System.out.println("通知 " + inserted + ": receiver=" + receiverId
                    + ", sender=" + senderId + ", type=" + (targetType == 1 ? "帖子" : "评论")
                    + ", target=" + targetId + ", comment=" + commentId);
        }

        System.out.println(">>> notification_comment 初始化完成！共写入 " + inserted + " 条记录");
    }

    // ======================== 测试数据写入 ========================

    /**
     * 第一步：批量注册用户
     */
    //@Test
    void seedUsers() throws Exception {
        ClassPathResource resource = new ClassPathResource("test-data/users.txt");
        List<String> lines = Files.readAllLines(resource.getFile().toPath(), StandardCharsets.UTF_8);

        int successCount = 0;
        int skipCount = 0;

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|");
            String username = parts[0];
            String nickname = parts[1];
            String password = parts[2];
            int userType = Integer.parseInt(parts[3]);

            User existing = authMapper.getByUsername(username);
            if (existing != null) {
                skipCount++;
                continue;
            }

            User user = new User();
            user.setUsername(username);
            user.setPassword(DigestUtils.md5DigestAsHex(password.getBytes()));
            user.setNickname(nickname);
            user.setAvatar(DEFAULT_AVATAR);

            int rows = authMapper.saveUser(user);
            if (rows <= 0 || user.getId() == null) {
                System.out.println("注册失败: " + username);
                continue;
            }

            usersMapper.initUserStatistics(user.getId());

            String statsKey = USER_STATS_KEY + user.getId();
            redisTemplate.opsForHash().putAll(statsKey, Map.of(
                    "followingCount", 0,
                    "followersCount", 0,
                    "statusesCount", 0,
                    "likesCount", 0
            ));

            if (userType == 1) {
                testMapper.updateUserType(user.getId(), 1);
            }

            successCount++;
        }

        System.out.println(">>> 用户注册完成！成功: " + successCount + ", 跳过(已存在): " + skipCount);
    }

    /**
     * 第二步：批量发布微博
     */
    //@Test
    void seedStatuses() throws Exception {
        ClassPathResource resource = new ClassPathResource("test-data/statuses.txt");
        List<String> lines = Files.readAllLines(resource.getFile().toPath(), StandardCharsets.UTF_8);

        int successCount = 0;
        int failCount = 0;

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            int firstPipe = line.indexOf('|');
            if (firstPipe < 0) continue;
            String username = line.substring(0, firstPipe);
            String content = line.substring(firstPipe + 1);

            User user = authMapper.getByUsername(username);
            if (user == null) {
                System.out.println("用户不存在，跳过: " + username);
                failCount++;
                continue;
            }

            try {
                SaveStatusVO saveStatusVO = new SaveStatusVO();
                saveStatusVO.setContent(content);

                int rows = testMapper.insertStatus(user.getId(), saveStatusVO);
                if (rows <= 0 || saveStatusVO.getId() == null) {
                    System.out.println("微博插入失败: user=" + username);
                    failCount++;
                    continue;
                }

                Long statusId = saveStatusVO.getId();
                String postStatsKey = POST_STATS_KEY + statusId;
                long createTime = saveStatusVO.getCreatedAt() != null
                        ? saveStatusVO.getCreatedAt().toEpochSecond(ZoneOffset.UTC)
                        : System.currentTimeMillis() / 1000;

                redisTemplate.opsForHash().putAll(postStatsKey, Map.of(
                        "likeCount", 0, "commentCount", 0,
                        "forwardCount", 0, "viewCount", 0,
                        "createTime", createTime
                ));

                String userPostIdsKey = USER_POST_IDS_KEY + user.getId();
                redisTemplate.opsForZSet().add(userPostIdsKey, statusId.toString(), createTime);

                try {
                    FeedMessage feedMessage = FeedMessage.builder()
                            .messageId(UUID.randomUUID().toString())
                            .userId(user.getId()).statusId(statusId)
                            .createTime(createTime).retryCount(0).build();
                    rabbitTemplate.convertAndSend("weibo.topic", "feed.publish",
                            feedMessage, new CorrelationData(feedMessage.getMessageId()));
                } catch (Exception e) {
                    // MQ 降级
                }

                successCount++;
                if (successCount % 300 == 0) {
                    System.out.println("已发布 " + successCount + " 条微博...");
                }
            } catch (Exception e) {
                failCount++;
                System.out.println("发布异常: user=" + username + ", error=" + e.getMessage());
            }
        }

        System.out.println(">>> 微博发布完成！成功: " + successCount + ", 失败: " + failCount);
    }

    // ======================== 互动评论内容模板 ========================

    private static final String[] COMMENT_TEMPLATES = {
            "说得好！", "太棒了！", "支持！", "学习了！", "感谢分享！",
            "同意！说得对", "确实如此", "这个观点很有意思", "楼主说得有道理",
            "涨知识了", "mark一下", "已阅", "不错不错", "好文章！",
            "坐等更新", "期待后续", "收藏了", "顶一个", "前排围观",
            "路过看看", "讲得很透彻", "一针见血", "说到心坎里了",
            "太真实了", "笑死我了哈哈", "哈哈哈哈", "就很棒",
            "这也太强了吧", "厉害了", "respect!", "佩服佩服",
            "想试试", "求教程", "带带我", "在哪里学的",
            "同问+1", "这个我也有", "同款", "我也有同感",
            "现在才知道", "原来是这样", "恍然大悟", "学到了新知识",
            "太有意思了", "精辟", "收藏了慢慢看", "好家伙",
    };

    private static final String[] COMMENT_REPLY_TEMPLATES = {
            "谢谢！", "感谢支持～", "你说得对", "确实是这样",
            "一起学习", "哈哈是吧", "对的！", "我也这么觉得",
            "+1", "没错没错", "握手", "英雄所见略同",
            "已关注你", "互关一下", "谢谢老铁", "点赞了",
    };

    // ======================== 第三步：关注关系 ========================

    //@Test
    void seedFollows() {
        Random random = new Random(42);

        List<User> allUsers = testMapper.selectListusers(null);
        if (allUsers == null || allUsers.isEmpty()) {
            System.out.println("没有用户数据，请先执行 seedUsers");
            return;
        }

        List<User> influencers = allUsers.stream()
                .filter(u -> u.getUserType() != null && u.getUserType() == 1)
                .toList();
        List<User> normals = allUsers.stream()
                .filter(u -> u.getUserType() == null || u.getUserType() == 0)
                .toList();

        System.out.println(">>> 开始建立关注关系...");
        System.out.println("   大V用户: " + influencers.size() + ", 普通用户: " + normals.size());

        int totalFollows = 0;
        long startTime = System.currentTimeMillis();

        // 普通用户随机关注 3-15 个大V
        for (User normal : normals) {
            int targetCount = Math.min(random.nextInt(13) + 3, influencers.size());
            List<User> targets = new ArrayList<>(influencers);
            Collections.shuffle(targets, random);
            targets = targets.subList(0, targetCount);

            for (User target : targets) {
                try {
                    follow(normal.getId(), target.getId());
                    totalFollows++;
                } catch (Exception e) {
                    System.out.println("关注失败: " + normal.getId() + " -> " + target.getId() + ": " + e.getMessage());
                }
            }

            if (totalFollows % 200 == 0) {
                System.out.println("已建立 " + totalFollows + " 条关注关系...");
            }
        }

        // 大V之间互相关注
        for (int i = 0; i < influencers.size(); i++) {
            int followCount = random.nextInt(5) + 1;
            List<User> otherInfluencers = new ArrayList<>(influencers);
            otherInfluencers.remove(i);
            Collections.shuffle(otherInfluencers, random);
            int maxFollow = Math.min(followCount, otherInfluencers.size());
            for (int j = 0; j < maxFollow; j++) {
                try {
                    follow(influencers.get(i).getId(), otherInfluencers.get(j).getId());
                    totalFollows++;
                } catch (Exception e) {
                    // 可能已关注，忽略
                }
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println(">>> 关注关系建立完成！共 " + totalFollows + " 条，耗时 " + elapsed + "s");
    }

    private void follow(Long currentUserId, Long targetUserId) {
        int r1 = followMapper.saveFollower(currentUserId, targetUserId);
        int r2 = followMapper.saveFollowing(currentUserId, targetUserId);
        if (r1 <= 0 || r2 <= 0) return;

        followMapper.updateFollowingCount(currentUserId, 1);
        followMapper.updateFollowersCount(targetUserId, 1);

        redisTemplate.opsForSet().add(FOLLOW_FOLLOWING_KEY + currentUserId, targetUserId.toString());
        redisTemplate.opsForSet().add(FOLLOW_FANS_KEY + targetUserId, currentUserId.toString());

        redisTemplate.opsForHash().increment(USER_STATS_KEY + currentUserId, "followingCount", 1);
        redisTemplate.opsForHash().increment(USER_STATS_KEY + targetUserId, "followersCount", 1);

        String feedKey = FEED_USER_KEY + currentUserId;
        List<Statuses> recentPosts = followMapper.getRecentPostsByUserId(targetUserId);
        if (recentPosts != null && !recentPosts.isEmpty()) {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Statuses post : recentPosts) {
                    double score = post.getCreatedAt().toEpochSecond(ZoneOffset.UTC);
                    connection.zSetCommands().zAdd(feedKey.getBytes(), score, post.getId().toString().getBytes());
                }
                return null;
            });
        }
    }

    // ======================== 第四步：批量点赞 ========================

    //@Test
    void seedLikes() {
        Random random = new Random(42);

        List<Statuses> allPosts = testMapper.selectList(null);
        List<User> allUsers = testMapper.selectListusers(null);
        if (allPosts == null || allPosts.isEmpty() || allUsers == null || allUsers.isEmpty()) {
            System.out.println("没有帖子和用户数据，请先执行 seedUsers + seedStatuses");
            return;
        }

        System.out.println(">>> 开始生成点赞数据...");
        int totalLikes = 0;
        int batchCount = 0;
        long startTime = System.currentTimeMillis();

        for (Statuses post : allPosts) {
            Long postId = post.getId();
            Long postOwnerId = post.getUserId();

            User owner = allUsers.stream().filter(u -> u.getId().equals(postOwnerId)).findFirst().orElse(null);
            boolean isInfluencer = owner != null && owner.getUserType() != null && owner.getUserType() == 1;

            double likeRate = isInfluencer ? 0.15 + random.nextDouble() * 0.35 : 0.02 + random.nextDouble() * 0.13;
            int likeCount = Math.max(1, (int) (allUsers.size() * likeRate));

            List<User> candidates = new ArrayList<>(allUsers);
            candidates.removeIf(u -> u.getId().equals(postOwnerId));
            Collections.shuffle(candidates, random);
            int actualCount = Math.min(likeCount, candidates.size());

            for (int i = 0; i < actualCount; i++) {
                Long userId = candidates.get(i).getId();
                try {
                    likePost(userId, postId, postOwnerId);
                    totalLikes++;
                } catch (Exception e) {
                    // 可能重复点赞，忽略
                }
            }

            batchCount++;
            if (batchCount % 500 == 0) {
                System.out.println("处理点赞：" + batchCount + "/" + allPosts.size() + " 条帖子...");
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println(">>> 点赞数据生成完成！共 " + totalLikes + " 个点赞，耗时 " + elapsed + "s");
    }

    private void likePost(Long userId, Long statusId, Long postOwnerId) {
        String likeKey = LIKE_POST_KEY + statusId;
        String postStatsKey = POST_STATS_KEY + statusId;

        Long added = redisTemplate.opsForSet().add(likeKey, userId.toString());
        if (added == null || added != 1) return;

        // 1. Redis: post:stats likeCount +1
        redisTemplate.opsForHash().increment(postStatsKey, "likeCount", 1);

        // 2. 帖子作者的获赞数 +1
        if (postOwnerId != null) {
            postMapper.updateStatistics(1, postOwnerId);
            redisTemplate.opsForHash().increment(USER_STATS_KEY + postOwnerId, "likesCount", 1);
        }

        // 3. 消费者 LikeConsumer: 写入 attitudes 表 + 更新 statuses.attitudes_count
        try {
            ToggleLikeDTO likeDTO = new ToggleLikeDTO();
            likeDTO.setUserId(userId);
            likeDTO.setTargetId(statusId);
            likeDTO.setTargetType(com.jun.enumeration.TargetType.POST);
            postMapper.saveLikeInfo(likeDTO);
            postMapper.updateStatusLikeCount(1, statusId);
        } catch (Exception e) {
            System.out.println("LikeConsumer 落库失败: " + e.getMessage());
        }

        // 4. 消费者 NoticeLikeConsumer: 写入 notification_like
        try {
            Long receiverId = testMapper.getOwnerByTargetId(1, statusId);
            if (receiverId != null && !receiverId.equals(userId)) {
                testMapper.upsertLikeNotificationSeed(receiverId, 1, statusId);
            }
        } catch (Exception e) {
            System.out.println("NoticeLikeConsumer 落库失败: " + e.getMessage());
        }

        // 5. 更新热度分
        updateHotScore(postStatsKey, statusId);
    }

    // ======================== 第五步：批量评论 ========================

    //@Test
    void seedComments() {
        Random random = new Random(42);

        List<Statuses> allPosts = testMapper.selectList(null);
        List<User> allUsers = testMapper.selectListusers(null);
        if (allPosts == null || allPosts.isEmpty()) {
            System.out.println("没有帖子数据，请先执行 seedStatuses");
            return;
        }

        System.out.println(">>> 开始生成评论数据...");
        int totalComments = 0;
        long startTime = System.currentTimeMillis();

        List<long[]> commentRecords = new ArrayList<>();

        // 一级评论
        for (Statuses post : allPosts) {
            User owner = allUsers.stream().filter(u -> u.getId().equals(post.getUserId())).findFirst().orElse(null);
            boolean isInfluencer = owner != null && owner.getUserType() != null && owner.getUserType() == 1;

            int commentCount = isInfluencer ? random.nextInt(11) + 5 : random.nextInt(4);
            if (commentCount == 0) continue;

            List<User> candidates = new ArrayList<>(allUsers);
            candidates.removeIf(u -> u.getId().equals(post.getUserId()));
            Collections.shuffle(candidates, random);
            int actualCount = Math.min(commentCount, candidates.size());

            for (int i = 0; i < actualCount; i++) {
                Long userId = candidates.get(i).getId();
                String content = COMMENT_TEMPLATES[random.nextInt(COMMENT_TEMPLATES.length)];
                if (random.nextDouble() < 0.3) {
                    content += " " + (random.nextInt(100) + 1);
                }
                try {
                    Long commentId = postComment(userId, post.getId(), null, null, content);
                    if (commentId != null) {
                        commentRecords.add(new long[]{commentId, post.getId(), userId});
                        totalComments++;
                    }
                } catch (Exception e) {
                    System.out.println("评论失败: user=" + userId + ", post=" + post.getId() + ": " + e.getMessage());
                }
            }
        }

        // 回复评论
        int replyCount = 0;
        for (long[] record : commentRecords) {
            if (random.nextDouble() < 0.2) {
                long parentCommentId = record[0];
                long statusId = record[1];
                long parentUserId = record[2];

                int replies = random.nextInt(3) + 1;
                List<User> replyCandidates = new ArrayList<>(allUsers);
                replyCandidates.removeIf(u -> u.getId().equals(parentUserId));
                Collections.shuffle(replyCandidates, random);
                int actualReplies = Math.min(replies, replyCandidates.size());

                for (int i = 0; i < actualReplies; i++) {
                    Long replyUserId = replyCandidates.get(i).getId();
                    String content = COMMENT_REPLY_TEMPLATES[random.nextInt(COMMENT_REPLY_TEMPLATES.length)];
                    try {
                        postComment(replyUserId, statusId, parentCommentId, parentUserId, content);
                        replyCount++;
                        totalComments++;
                    } catch (Exception e) {
                        // 忽略
                    }
                }
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println(">>> 评论数据生成完成！一级评论: " + (totalComments - replyCount)
                + ", 回复: " + replyCount + ", 总计: " + totalComments + "，耗时 " + elapsed + "s");
    }

    private Long postComment(Long userId, Long statusId, Long parentId, Long replyUserId, String content) {
        Long postExists = testMapper.getPostById(statusId);
        if (postExists == null) return null;

        if (parentId != null && replyUserId == null) {
            Long ownerId = testMapper.getCommentOwnerId(parentId);
            if (ownerId == null) return null;
            replyUserId = ownerId;
        }

        LocalDateTime now = LocalDateTime.now();
        Comment comment = Comment.builder()
                .statusId(statusId).userId(userId)
                .parentId(parentId).replyUserId(replyUserId)
                .content(content).createdAt(now).build();

        int rows = testMapper.saveComment(comment);
        if (rows <= 0 || comment.getId() == null) return null;
        Long commentId = comment.getId();

        // 1. Redis: 帖子评论计数 +1
        String postStatsKey = POST_STATS_KEY + statusId;
        redisTemplate.opsForHash().increment(postStatsKey, "commentCount", 1);

        // 2. Redis: 如果是回复，父评论的 replyCount +1
        if (parentId != null) {
            redisTemplate.opsForHash().increment(COMMENT_STATS_KEY + parentId, "replyCount", 1);
        }

        // 3. 清除评论列表缓存
        redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":1:3");
        redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":1:5");
        redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":1:10");
        redisTemplate.delete(POST_COMMENT_LIST_KEY + statusId + ":1:20");

        // 4. 消费者 CommentConsumer: 更新 statuses.comments_count
        try {
            testMapper.updateStatusCommentCount(statusId);
        } catch (Exception e) {
            System.out.println("CommentConsumer 落库失败: " + e.getMessage());
        }

        // 5. 消费者 NoticeCommentConsumer: 写入 notification_comment
        try {
            Long receiverId;
            Integer targetType;
            Long targetId;
            if (parentId == null) {
                receiverId = testMapper.getPostOwnerId(statusId);
                targetType = 1;
                targetId = statusId;
            } else {
                receiverId = replyUserId;
                targetType = 2;
                targetId = parentId;
            }
            if (receiverId != null && !receiverId.equals(userId)) {
                testMapper.insertCommentNotification(receiverId, userId, targetType, targetId, commentId, now);
            }
        } catch (Exception e) {
            System.out.println("NoticeCommentConsumer 落库失败: " + e.getMessage());
        }

        // 6. 更新热度分
        updateHotScore(postStatsKey, statusId);

        return commentId;
    }

    // ======================== 第六步：批量转发 ========================

    //@Test
    void seedReposts() {
        Random random = new Random(42);

        List<Statuses> allPosts = testMapper.selectList(null);
        List<User> allUsers = testMapper.selectListusers(null);
        if (allPosts == null || allPosts.isEmpty()) {
            System.out.println("没有帖子数据，请先执行 seedStatuses");
            return;
        }

        System.out.println(">>> 开始生成转发数据...");
        int totalReposts = 0;
        long startTime = System.currentTimeMillis();

        for (Statuses post : allPosts) {
            User owner = allUsers.stream().filter(u -> u.getId().equals(post.getUserId())).findFirst().orElse(null);
            boolean isInfluencer = owner != null && owner.getUserType() != null && owner.getUserType() == 1;

            double repostRate = isInfluencer ? 0.10 + random.nextDouble() * 0.20 : 0.01 + random.nextDouble() * 0.04;
            int repostCount = Math.max(0, (int) (allUsers.size() * repostRate));

            List<User> candidates = new ArrayList<>(allUsers);
            candidates.removeIf(u -> u.getId().equals(post.getUserId()));
            Collections.shuffle(candidates, random);
            int actualCount = Math.min(repostCount, candidates.size());

            for (int i = 0; i < actualCount; i++) {
                try {
                    repostPost(candidates.get(i).getId(), post.getId());
                    totalReposts++;
                } catch (Exception e) {
                    // 忽略
                }
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println(">>> 转发数据生成完成！共 " + totalReposts + " 条，耗时 " + elapsed + "s");
    }

    private void repostPost(Long userId, Long forwardId) {
        Long originalStatusId = postMapper.getOriginalStatus(forwardId);
        if (originalStatusId == null) {
            originalStatusId = forwardId;
        }

        // 1. Redis: 原微博 forwardCount +1
        String postStatsKey = POST_STATS_KEY + originalStatusId;
        redisTemplate.opsForHash().increment(postStatsKey, "forwardCount", 1);

        // 2. 消费者 RepostConsumer: 创建转发微博 + 初始化 Redis + 更新 reposts_count
        try {
            LocalDateTime now = LocalDateTime.now();
            Statuses forwardStatus = Statuses.builder()
                    .userId(userId)
                    .content("转发微博")
                    .statusType(1)
                    .originalStatusId(originalStatusId)
                    .createdAt(now)
                    .build();
            testMapper.saveForwardStatus(forwardStatus);

            Long forwardStatusId = forwardStatus.getId();
            if (forwardStatusId != null) {
                long createTimeStamp = now.toEpochSecond(ZoneOffset.UTC);

                // 写入用户 timeline ZSET
                String zsetKey = USER_POST_IDS_KEY + userId;
                redisTemplate.opsForZSet().add(zsetKey, forwardStatusId.toString(), createTimeStamp);

                // 初始化新转发微博的统计数据
                String statsKey = POST_STATS_KEY + forwardStatusId;
                Map<String, Object> map = new HashMap<>();
                map.put("likeCount", 0);
                map.put("commentCount", 0);
                map.put("viewCount", 0);
                map.put("forwardCount", 0);
                map.put("createTime", createTimeStamp);
                redisTemplate.opsForHash().putAll(statsKey, map);

                // 原微博 reposts_count +1
                postMapper.updateStatusRepostCount(1, originalStatusId);
            }
        } catch (Exception e) {
            System.out.println("RepostConsumer 落库失败: " + e.getMessage());
        }

        // 3. 更新热度分
        updateHotScore(postStatsKey, originalStatusId);
    }

    // ======================== 热度分更新工具 ========================

    private void updateHotScore(String postStatsKey, Long statusId) {
        Map<Object, Object> postStats = redisTemplate.opsForHash().entries(postStatsKey);
        if (postStats == null || postStats.isEmpty()) return;

        long likeCount = parseStat(postStats, "likeCount");
        long commentCount = parseStat(postStats, "commentCount");
        long forwardCount = parseStat(postStats, "forwardCount");
        long viewCount = parseStat(postStats, "viewCount");
        long createTime = parseStat(postStats, "createTime");

        boolean hasValidInteraction = (likeCount > 0 || commentCount > 0 || forwardCount > 0);
        if (!hasValidInteraction) {
            redisTemplate.opsForSet().remove(HOT_POST_CANDIDATE_KEY, statusId.toString());
            redisTemplate.opsForZSet().remove(HOT_POST_ZSET_KEY, statusId.toString());
            return;
        }

        double score = HotScoreCalculator.calculate(viewCount, likeCount, commentCount, forwardCount, createTime);
        redisTemplate.opsForZSet().add(HOT_POST_ZSET_KEY, statusId.toString(), score);
        redisTemplate.opsForSet().add(HOT_POST_CANDIDATE_KEY, statusId.toString());
    }

    private long parseStat(Map<Object, Object> stats, String field) {
        Object obj = stats.get(field);
        if (obj == null) return 0;
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}