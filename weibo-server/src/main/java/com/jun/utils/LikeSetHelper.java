package com.jun.utils;

import com.jun.constant.RedisConstants;
import com.jun.enumeration.TargetType;
import com.jun.mapper.PostMapper;
import com.jun.vo.LikeRecordVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 点赞用户集合缓存（like:post:{postId} / like:comment:{commentId}，SET of userId）的统一维护入口。
 * 数据源：attitudes 表（点赞 MQ 消费落库，物理删除；target_type=1 帖子 / 2 评论）。
 * <p>
 * - TTL：重建与写入时 7 天，避免全站点赞集合永久占用内存；
 * - 写路径（点赞/取消）：add/remove 前条件重建，保证 SADD/SREM 返回值反映数据库真实
 *   状态——key 丢失时若直接 add 会把已点赞用户当作新赞，导致 likeCount 重复累加与
 *   MQ 重复落库；若直接 remove 会把已点赞的取消操作误判为幂等，DB 记录无法删除；
 * - 读路径（isLiked 展示）：批量条件重建兜底，key 缺失时从数据库批量拉回，SADD 幂等
 *   无锁安全，与并发点赞最终一致；
 * - 空集合哨兵：零赞目标不落 SADD 成员，仅写哨兵成员 "0" 占位使 key 存在并带 TTL，
 *   避免零赞目标每次浏览都触发重建查库；userId 恒为正整数，哨兵不影响 isMember 判断。
 */
@Slf4j
@Component
public class LikeSetHelper {

    /** 空集合哨兵成员：userId 恒为正整数，"0" 不会与真实用户冲突 */
    private static final String EMPTY_SENTINEL = "0";

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private PostMapper postMapper;

    private String keyOf(TargetType type, Long targetId) {
        return (type == TargetType.POST
                ? RedisConstants.LIKE_POST_KEY
                : RedisConstants.LIKE_COMMENT_KEY) + targetId;
    }

    /**
     * 写路径：确保点赞集合存在（key 缺失时从数据库重建），点赞/取消操作前调用。
     * 异常仅记录日志不传播，失败时退化为原行为（SADD/SREM 直接执行）。
     */
    public void ensureLikeSet(TargetType type, Long targetId) {
        if (targetId == null) {
            return;
        }
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(keyOf(type, targetId)))) {
                rebuild(type, List.of(targetId));
            }
        } catch (Exception e) {
            log.warn("确保点赞集合缓存存在时异常: type={}, targetId={}", type, targetId, e);
        }
    }

    /**
     * 读路径：批量确保点赞集合存在（pipeline EXISTS 探测，miss 批量重建），
     * isLiked 展示查询前调用，防止 key 过期后已点赞显示为未点赞。
     * 异常仅记录日志不传播。
     */
    public void ensureLikeSets(TargetType type, List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return;
        }
        try {
            List<Object> exists = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long targetId : targetIds) {
                    connection.keyCommands().exists(keyOf(type, targetId).getBytes());
                }
                return null;
            });

            List<Long> missIds = new ArrayList<>();
            for (int i = 0; i < targetIds.size(); i++) {
                Object e = exists.get(i);
                if (!Boolean.TRUE.equals(e)) {
                    missIds.add(targetIds.get(i));
                }
            }
            if (!missIds.isEmpty()) {
                rebuild(type, missIds);
            }
        } catch (Exception e) {
            log.warn("批量确保点赞集合缓存存在时异常: type={}, count={}", type, targetIds.size(), e);
        }
    }

    /**
     * 写路径：点赞/取消成功后续期，活跃内容的点赞集合不过期
     */
    public void refreshTtl(TargetType type, Long targetId) {
        redisTemplate.expire(keyOf(type, targetId), RedisConstants.LIKE_SET_TTL, TimeUnit.SECONDS);
    }

    /**
     * 从数据库重建点赞集合：批量查询 attitudes 写回（SADD 幂等 + 哨兵占位 + TTL）。
     * 无锁：并发重建/点赞写入的是同一份 DB 快照，SADD 幂等最终一致；
     * 与取消点赞消费并发时极小窗口可能复活已取消的成员，下次取消或重建自愈。
     */
    private void rebuild(TargetType type, List<Long> targetIds) {
        List<LikeRecordVO> records = postMapper.getLikeRecords(type.getCode(), targetIds);

        Map<Long, Set<String>> likeMap = new HashMap<>();
        for (LikeRecordVO record : records) {
            likeMap.computeIfAbsent(record.getTargetId(), k -> new HashSet<>())
                    .add(record.getUserId().toString());
        }

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long targetId : targetIds) {
                byte[] key = keyOf(type, targetId).getBytes();
                // 哨兵占位：即使零赞也让 key 存在并带 TTL，避免反复重建查库
                connection.setCommands().sAdd(key, EMPTY_SENTINEL.getBytes());
                Set<String> users = likeMap.get(targetId);
                if (users != null && !users.isEmpty()) {
                    byte[][] members = new byte[users.size()][];
                    int m = 0;
                    for (String user : users) {
                        members[m++] = user.getBytes();
                    }
                    connection.setCommands().sAdd(key, members);
                }
                connection.keyCommands().expire(key, RedisConstants.LIKE_SET_TTL);
            }
            return null;
        });
        log.info("重建点赞集合缓存: type={}, count={}", type, targetIds.size());
    }
}
