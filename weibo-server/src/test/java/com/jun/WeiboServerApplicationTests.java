package com.jun;

import com.jun.entity.Statuses;
import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import com.jun.mapper.AttitudeAggregate;
import com.jun.mapper.TestMapper;
import com.jun.utils.HotScoreCalculator;
import com.jun.vo.StatusVO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.CollectionUtils;

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

    //初始化redis所有用户发的微博id统计
    //@Test
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

    //初始化redis微博统计数据
    @Test
    void initPostStatsToRedis() {
        System.out.println(">>> 开始从数据库加载微博数据并同步到 Redis...");

        List<Statuses> statusesList = testMapper.selectList(null);

        if (statusesList == null || statusesList.isEmpty()) {
            System.out.println("数据库中没有微博数据。");
            return;
        }

        // 1. 获取模板配置的序列化器（用于处理 Key 和 Field）
        // 这保证了字段的序列化方式和你应用其他部分保持一致
        var keySerializer = redisTemplate.getKeySerializer();
        var hashKeySerializer = redisTemplate.getHashKeySerializer();

        // 2. 关键：创建专门的 String 序列化器，仅用于处理 Value
        // 这能确保存入的值是纯文本，如 0，而不是 "0"
        StringRedisSerializer stringValueSerializer = new StringRedisSerializer();

        System.out.println("开始写入 Redis，共 " + statusesList.size() + " 条数据...");

        // 3. 使用 Pipeline 批量写入
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

            for (Statuses status : statusesList) {
                // 使用模板的 Key 序列化器来生成 Redis Key
                byte[] rawKey = keySerializer.serialize(POST_STATS_KEY + status.getId());

                long createTimeStamp = status.getCreatedAt().toEpochSecond(ZoneOffset.UTC);

                Map<String, Object> hashData = new HashMap<>();
                hashData.put("likeCount", status.getAttitudesCount() != null ? status.getAttitudesCount() : 0);
                hashData.put("commentCount", status.getCommentsCount() != null ? status.getCommentsCount() : 0);
                hashData.put("forwardCount", status.getRepostsCount() != null ? status.getRepostsCount() : 0);
                hashData.put("viewCount", status.getViewsCount() != null ? status.getViewsCount() : 0);
                hashData.put("createTime", createTimeStamp);

                // 4. 遍历数据，分别用不同的序列化器处理 Field 和 Value
                for (Map.Entry<String, Object> entry : hashData.entrySet()) {
                    // Field 使用模板配置的序列化器 (可能带引号)
                    byte[] field = hashKeySerializer.serialize(entry.getKey());

                    // Value 强制使用 String 序列化器 (不带引号)
                    byte[] value = stringValueSerializer.serialize(String.valueOf(entry.getValue()));

                    // 执行 HSET 命令
                    connection.hSet(rawKey, field, value);
                }
            }
            return null;
        });

        System.out.println(">>> 同步完成！共处理 " + statusesList.size() + " 条微博统计信息。");
    }

    //初始化user_statistics
    //@Test
    void initUserStatsTable() {
        System.out.println(">>> 开始初始化用户统计表...");

        // 1. 查询所有用户
        List<User> users = testMapper.selectListusers(null);

        if (users == null || users.isEmpty()) {
            System.out.println("用户表为空，无需初始化。");
            return;
        }

        List<UserStatistics> statsListToSave = new ArrayList<>();

        // 2. 遍历并构建数据
        for (User user : users) {
            UserStatistics stats = new UserStatistics();

            // 关联 User ID
            stats.setUserId(user.getId());

            // 初始化计数器为 0
            stats.setFollowingCount(0);
            stats.setFollowersCount(0);
            stats.setStatusesCount(0);
            stats.setLikesCount(0);

            // 设置更新时间
            stats.setUpdatedAt(LocalDateTime.now());

            statsListToSave.add(stats);
        }

        // 3. 批量保存 (推荐使用批量操作以提高性能)
        // 注意：如果你使用的是 MyBatis-Plus，saveBatch 是内置方法
        // 如果是原生 MyBatis，请调用对应的 xml 批量插入方法
        boolean result = testMapper.insertBatchSomeColumn(statsListToSave); // 这里假设你有批量插入的方法

        if (result) {
            System.out.println(">>> 初始化成功！共处理 " + statsListToSave.size() + " 条记录。");
        } else {
            System.out.println(">>> 初始化失败或无数据变更。");
        }
    }

    // 从 Redis 读取粉丝数量和关注数量，写入 user_statistics 表
    //@Test
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

            // 从 Redis Hash 读取关注数
            Object followingObj = redisTemplate.opsForHash().get(statsKey, "followingCount");
            int followingCount = 0;
            if (followingObj != null) {
                followingCount = Integer.parseInt(followingObj.toString());
            }

            // 从 Redis Hash 读取粉丝数
            Object fansObj = redisTemplate.opsForHash().get(statsKey, "followersCount");
            int followersCount = 0;
            if (fansObj != null) {
                followersCount = Integer.parseInt(fansObj.toString());
            }

            // 只有当 Redis 中有数据时才更新
            if (followingCount > 0 || followersCount > 0) {
                testMapper.updateUserFollowCounts(userId, followingCount, followersCount);
                updated++;
                System.out.println("用户 " + userId + ": following=" + followingCount + ", followers=" + followersCount);
            }
        }

        System.out.println(">>> 同步完成！共更新 " + updated + " 条记录。");
    }

    /**
     * 初始化粉丝收件箱：将所有历史帖子写入粉丝的 feed:user ZSET
     */
    //@Test
    void initFanInboxes() {
        System.out.println(">>> 开始初始化粉丝收件箱...");

        // 1. 获取所有发过帖子的用户
        Set<Long> userIds = testMapper.getUserId();
        if (userIds == null || userIds.isEmpty()) {
            System.out.println("没有用户数据，跳过。");
            return;
        }

        int totalFanPushes = 0;

        for (Long userId : userIds) {
            // 2. 该用户的帖子列表
            List<Statuses> posts = testMapper.getStatusByUserId(userId);
            if (posts == null || posts.isEmpty()) continue;

            // 3. 该用户的粉丝列表（Redis）
            Set<String> fans = redisTemplate.opsForSet().members(FOLLOW_FANS_KEY + userId);
            if (fans == null || fans.isEmpty()) continue;

            // 4. pipeline 批量推送到粉丝收件箱
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String fanId : fans) {
                    byte[] feedKey = (FEED_USER_KEY + fanId).getBytes();
                    for (Statuses post : posts) {
                        double score = post.getCreatedAt().toEpochSecond(ZoneOffset.UTC);
                        connection.zAdd(
                                feedKey,
                                score,
                                post.getId().toString().getBytes()
                        );
                    }
                }
                return null;
            });

            totalFanPushes += fans.size();
            System.out.println("用户 " + userId + " 的 " + posts.size() + " 条帖子 → " + fans.size() + " 个粉丝收件箱");
        }

        System.out.println(">>> 粉丝收件箱初始化完成！共影响 " + totalFanPushes + " 个粉丝收件箱");
    }

    /**
     * 根据点赞表初始化通知 like 表：
     * 将 attitudes 按 (target_type, target_id) 聚合后写入 notification_like，接收者为微博/评论的作者。
     */
    @Test
    void initNotificationLike() {
        System.out.println(">>> 开始初始化 notification_like 表...");

        // 1. 聚合 attitudes 数据
        List<AttitudeAggregate> aggregates = testMapper.getAttitudeAggregates();
        if (aggregates == null || aggregates.isEmpty()) {
            System.out.println("点赞表无数据，跳过。");
            return;
        }

        int inserted = 0;
        for (AttitudeAggregate agg : aggregates) {
            if (agg.getTargetType() == null || agg.getTargetId() == null) {
                continue;
            }

            // 2. 获取目标作者（即通知接收者）
            Long receiverId;
            if (agg.getTargetType() == 1) {
                receiverId = testMapper.getPostOwnerId(agg.getTargetId());
            } else if (agg.getTargetType() == 2) {
                receiverId = testMapper.getCommentOwnerId(agg.getTargetId());
            } else {
                continue;
            }

            if (receiverId == null) {
                continue;
            }

            // 3. Upsert 通知表
            testMapper.upsertNotificationLike(receiverId, agg.getTargetType(), agg.getTargetId(),
                    agg.getLikeCount(), agg.getLastLikeTime());
            inserted++;

            System.out.println("通知 " + inserted + ": receiver=" + receiverId
                    + ", type=" + (agg.getTargetType() == 1 ? "帖子" : "评论")
                    + ", target=" + agg.getTargetId()
                    + ", likes=" + agg.getLikeCount());
        }

        System.out.println(">>> notification_like 初始化完成！共写入 " + inserted + " 条记录");
    }
}

