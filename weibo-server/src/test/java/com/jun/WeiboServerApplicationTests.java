package com.jun;

import com.jun.entity.Statuses;
import com.jun.entity.User;
import com.jun.entity.UserStatistics;
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
    //@Test
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

                Map<String, String> hashData = new HashMap<>();
                hashData.put("likeCount", "0");
                hashData.put("commentCount", "0");
                hashData.put("forwardCount", "0");
                hashData.put("viewCount", "0");
                hashData.put("createTime", String.valueOf(createTimeStamp));

                // 4. 遍历数据，分别用不同的序列化器处理 Field 和 Value
                for (Map.Entry<String, String> entry : hashData.entrySet()) {
                    // Field 使用模板配置的序列化器 (可能带引号)
                    byte[] field = hashKeySerializer.serialize(entry.getKey());

                    // Value 强制使用 String 序列化器 (不带引号)
                    byte[] value = stringValueSerializer.serialize(entry.getValue());

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
}

