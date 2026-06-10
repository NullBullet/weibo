package com.jun.service.impl;

import cn.hutool.json.JSONUtil;
import com.jun.context.BaseContext;
import com.jun.dto.HomeQueryDTO;
import com.jun.entity.StatusImage;
import com.jun.mapper.HotMapper;
import com.jun.result.Result;
import com.jun.result.ScrollResult;
import com.jun.service.HomeService;
import com.jun.vo.HotStatusVO;
import com.jun.vo.UserVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static com.jun.constant.RedisConstants.*;

@Slf4j
@Service
public class HomeServiceImpl implements HomeService {

    @Resource
    private HotMapper hotMapper;
    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public Result getPosts(HomeQueryDTO homeQueryDTO) {
        Long userId = BaseContext.getCurrentId();

        // 1. 分页参数处理
        Double lastScore = homeQueryDTO.getLastScore();
        Integer pageSize = homeQueryDTO.getPageSize();
        Integer offset = homeQueryDTO.getOffset();

        if (pageSize == null || pageSize <= 0) {
            pageSize = 10;
        }
        if (offset == null) {
            offset = 0;
        }
        if (lastScore == null) {
            lastScore = Double.POSITIVE_INFINITY;
        }

        // 2. 计算时间范围（startDate/endDate → epoch 秒）
        LocalDate startDate = homeQueryDTO.getStartDate();
        LocalDate endDate = homeQueryDTO.getEndDate();

        double minScore = Double.NEGATIVE_INFINITY;
        if (startDate != null) {
            minScore = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        }

        // 游标往前翻时，不能超过 endDate 的结束时间
        double maxScore = lastScore;
        if (endDate != null) {
            double endOfDay = endDate.atTime(LocalTime.MAX).toEpochSecond(ZoneOffset.UTC);
            maxScore = Math.min(lastScore, endOfDay);
        }

        // 3. 从收件箱 ZSET 获取 postIds
        String feedKey = FEED_USER_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(
                                feedKey,
                                minScore,
                                maxScore,
                                offset,
                                pageSize
                        );

        if (tuples == null || tuples.isEmpty()) {
            return Result.success(new ScrollResult(null, 0, Collections.emptyList()));
        }

        // 4. 批量从缓存获取帖子详情
        List<Long> statusIds = tuples.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .toList();

        List<String> keys = statusIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);

        // 5. 找缺失的帖子 ID（缓存未命中）
        List<HotStatusVO> result = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < statusIds.size(); i++) {
            String json = jsonList.get(i);
            Long id = statusIds.get(i);

            if (json != null && !json.isEmpty()) {
                result.add(JSONUtil.toBean(json, HotStatusVO.class));
            } else {
                missIds.add(id);
            }
        }

        // 6. 批量查询数据库（缓存缺失）
        if (!missIds.isEmpty()) {
            List<HotStatusVO> dbList = hotMapper.getPostByMissIds(missIds);

            if (!dbList.isEmpty()) {
                // 6.1 图片
                List<Long> missIdList = dbList.stream().map(HotStatusVO::getId).toList();
                List<StatusImage> images = hotMapper.getImageByIds(missIdList);
                Map<Long, List<StatusImage>> imageMap = images.stream()
                        .collect(Collectors.groupingBy(StatusImage::getStatusId));

                for (HotStatusVO vo : dbList) {
                    vo.setImages(imageMap.get(vo.getId()));
                }

                // 6.2 填充转发原微博
                fillOriginalStatus(dbList, userId);

                // 6.3 pipeline 回写缓存
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (HotStatusVO vo : dbList) {
                        String key = POST_DETAIL_KEY + vo.getId();
                        String value = JSONUtil.toJsonStr(vo);

                        long expireTime = POST_DETAIL_TTL + new Random().nextInt(300);
                        connection.stringCommands().set(
                                key.getBytes(),
                                value.getBytes(),
                                Expiration.seconds(expireTime),
                                RedisStringCommands.SetOption.UPSERT
                        );
                    }
                    return null;
                });

                result.addAll(dbList);
            }
        }

        // 7. 以 statusId 分类缓存
        Map<Long, HotStatusVO> map = result.stream()
                .collect(Collectors.toMap(HotStatusVO::getId, v -> v));

        // 8. 用户信息缓存
        // 8.1 收集 userId（去重）
        List<Long> userIdList = statusIds.stream()
                .map(id -> map.get(id).getUserId())
                .distinct()
                .toList();

        // 8.2 批量查 Redis
        List<String> userKeys = userIdList.stream()
                .map(uid -> USER_INFO_KEY + uid)
                .toList();
        List<String> userJsonList = redisTemplate.opsForValue().multiGet(userKeys);

        Map<Long, UserVO> userMap = new HashMap<>();
        List<Long> missUserIds = new ArrayList<>();

        for (int i = 0; i < userIdList.size(); i++) {
            Long uid = userIdList.get(i);
            String json = (userJsonList != null) ? userJsonList.get(i) : null;

            if (json != null && !json.isEmpty()) {
                userMap.put(uid, JSONUtil.toBean(json, UserVO.class));
            } else {
                missUserIds.add(uid);
            }
        }

        // 8.3 回源 DB
        if (!missUserIds.isEmpty()) {
            List<UserVO> dbUsers = hotMapper.getUsersInfoByUserIds(missUserIds);

            for (UserVO user : dbUsers) {
                userMap.put(user.getId(), user);
            }

            // 8.4 回写 Redis（pipeline）
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
                return null;
            });
        }

        // 9. 获取关注列表
        Set<Object> myFollowings = redisTemplate.opsForSet().members(FOLLOW_FOLLOWING_KEY + userId);
        Set<Long> myFollowingsIds = myFollowings != null ?
                myFollowings.stream()
                        .map(id -> Long.valueOf(id.toString()))
                        .collect(Collectors.toSet()) :
                Collections.emptySet();

        // 10. 构建 finalList
        List<HotStatusVO> finalList = new ArrayList<>();

        for (Long id : statusIds) {
            HotStatusVO vo = map.get(id);
            if (vo == null) continue;

            // 填充用户信息
            vo.setUser(userMap.get(vo.getUserId()));

            // 判断是否关注
            if (userId == null || !userId.equals(vo.getUserId())) {
                vo.setIsFollowed(myFollowingsIds.contains(vo.getUserId()));
            } else {
                vo.setIsFollowed(null);
            }

            finalList.add(vo);
        }

        // 11. 填充转发原微博并回写缓存
        fillOriginalStatus(finalList, userId);
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (HotStatusVO vo : finalList) {
                String cacheKey = POST_DETAIL_KEY + vo.getId();
                String cacheValue = JSONUtil.toJsonStr(vo);

                long expireTime = POST_DETAIL_TTL + new Random().nextInt(300);

                connection.stringCommands().set(
                        cacheKey.getBytes(),
                        cacheValue.getBytes(),
                        Expiration.seconds(expireTime),
                        RedisStringCommands.SetOption.UPSERT
                );
            }
            return null;
        });

        // 12. 获取即时统计数据（含原微博统计）
        for (HotStatusVO vo : finalList) {
            fillPostStats(vo);

            HotStatusVO originalStatus = vo.getOriginalStatus();
            if (originalStatus != null) {
                fillPostStats(originalStatus);
            }
        }

        // 13. 当前用户是否点赞（含原微博）
        // 13.1 收集所有微博的 LikeKey（含原微博）
        List<String> likeKeys = new ArrayList<>();
        for (HotStatusVO vo : finalList) {
            likeKeys.add(LIKE_POST_KEY + vo.getId());
            if (vo.getOriginalStatus() != null) {
                likeKeys.add(LIKE_POST_KEY + vo.getOriginalStatus().getId());
            }
        }

        // 13.2 批量检查
        List<Object> likeResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : likeKeys) {
                connection.setCommands().sIsMember(key.getBytes(), userId.toString().getBytes());
            }
            return null;
        });

        // 13.3 回填
        int idx = 0;
        for (HotStatusVO vo : finalList) {
            Boolean isMember = (Boolean) likeResults.get(idx++);
            vo.setIsLiked(isMember != null && isMember);

            if (vo.getOriginalStatus() != null) {
                Boolean origIsMember = (Boolean) likeResults.get(idx++);
                vo.getOriginalStatus().setIsLiked(origIsMember != null && origIsMember);
            }
        }

        // 14. 计算下一页游标
        double minTupleScore = tuples.stream()
                .mapToDouble(ZSetOperations.TypedTuple::getScore)
                .min()
                .orElse(0);

        int newOffset = 0;

        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getScore() == minTupleScore) {
                newOffset++;
            }
        }

        // 15. 返回
        return Result.success(new ScrollResult(minTupleScore, newOffset, finalList));
    }

    // ======================== 以下为私有辅助方法 ========================

    /**
     * 填充转发原微博
     */
    private void fillOriginalStatus(List<HotStatusVO> finalList, Long currentUserId) {
        // 1. 收集 originalIds
        List<Long> originalIds = finalList.stream()
                .filter(vo -> vo.getOriginalStatusId() != null)
                .filter(vo -> vo.getOriginalStatus() == null)
                .map(HotStatusVO::getOriginalStatusId)
                .distinct()
                .toList();

        if (originalIds.isEmpty()) {
            return;
        }

        // 2. 先查 Redis
        List<String> cacheKeys = originalIds.stream()
                .map(id -> POST_DETAIL_KEY + id)
                .toList();

        List<String> jsonList = redisTemplate.opsForValue().multiGet(cacheKeys);

        Map<Long, HotStatusVO> originalMap = new HashMap<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < originalIds.size(); i++) {
            Long id = originalIds.get(i);
            String json = jsonList != null ? jsonList.get(i) : null;

            if (json != null && !json.isEmpty()) {
                HotStatusVO vo = JSONUtil.toBean(json, HotStatusVO.class);
                originalMap.put(id, vo);
            } else {
                missIds.add(id);
            }
        }

        // 3. miss → DB
        if (!missIds.isEmpty()) {
            List<HotStatusVO> dbList = hotMapper.getPostByIds(missIds);

            if (!dbList.isEmpty()) {
                // 3.1 图片
                List<StatusImage> images = hotMapper.getImageByIds(missIds);
                Map<Long, List<StatusImage>> imageMap = images.stream()
                        .collect(Collectors.groupingBy(StatusImage::getStatusId));

                // 3.2 用户
                List<Long> userIds = dbList.stream()
                        .map(HotStatusVO::getUserId)
                        .distinct()
                        .toList();

                List<UserVO> users = hotMapper.getUsersInfoByUserIds(userIds);
                Map<Long, UserVO> userMap = users.stream().collect(Collectors.toMap(UserVO::getId, v -> v));

                for (HotStatusVO vo : dbList) {
                    vo.setImages(imageMap.get(vo.getId()));
                    vo.setUser(userMap.get(vo.getUserId()));
                    originalMap.put(vo.getId(), vo);
                }

                // 3.3 回写缓存
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (HotStatusVO vo : dbList) {
                        String key = POST_DETAIL_KEY + vo.getId();
                        String value = JSONUtil.toJsonStr(vo);
                        long expire = POST_DETAIL_TTL + new Random().nextInt(300);
                        connection.stringCommands().set(
                                key.getBytes(),
                                value.getBytes(),
                                Expiration.seconds(expire),
                                RedisStringCommands.SetOption.UPSERT
                        );
                    }
                    return null;
                });
            }
        }

        // 4. 批量填充实时统计
        List<Object> statsList = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long id : originalIds) {
                connection.hashCommands().hGetAll(
                        (POST_STATS_KEY + id).getBytes()
                );
            }
            return null;
        });

        for (int i = 0; i < originalIds.size(); i++) {
            Long id = originalIds.get(i);
            HotStatusVO vo = originalMap.get(id);
            if (vo == null) continue;

            Object obj = statsList.get(i);
            if (obj instanceof Map<?, ?> stats) {
                Object likeObj = stats.get("likeCount".getBytes());
                Object commentObj = stats.get("commentCount".getBytes());
                Object repostObj = stats.get("forwardCount".getBytes());
                Object viewObj = stats.get("viewCount".getBytes());

                vo.setAttitudesCount(likeObj == null ? 0 : Integer.parseInt(new String((byte[]) likeObj)));
                vo.setCommentsCount(commentObj == null ? 0 : Integer.parseInt(new String((byte[]) commentObj)));
                vo.setRepostsCount(repostObj == null ? 0 : Integer.parseInt(new String((byte[]) repostObj)));
                vo.setViewsCount(viewObj == null ? 0 : Integer.parseInt(new String((byte[]) viewObj)));
            }
        }

        // 5. 批量判断点赞
        if (currentUserId != null) {
            List<Object> likeResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long id : originalIds) {
                    connection.setCommands().sIsMember(
                            (LIKE_POST_KEY + id).getBytes(),
                            currentUserId.toString().getBytes()
                    );
                }
                return null;
            });

            for (int i = 0; i < originalIds.size(); i++) {
                HotStatusVO vo = originalMap.get(originalIds.get(i));
                if (vo == null) continue;

                Boolean liked = (Boolean) likeResults.get(i);
                vo.setIsLiked(liked != null && liked);
            }
        } else {
            originalMap.values().forEach(vo -> vo.setIsLiked(false));
        }

        // 6. 回填 originalStatus
        for (HotStatusVO vo : finalList) {
            if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() == null) {
                vo.setOriginalStatus(originalMap.get(vo.getOriginalStatusId()));
            }
        }
    }

    /**
     * 从 Redis 填充帖子的实时统计数据
     */
    private void fillPostStats(HotStatusVO vo) {
        String statsKey = POST_STATS_KEY + vo.getId();

        Object likeObj = redisTemplate.opsForHash().get(statsKey, "likeCount");
        Object commentObj = redisTemplate.opsForHash().get(statsKey, "commentCount");
        Object forwardObj = redisTemplate.opsForHash().get(statsKey, "forwardCount");
        Object viewObj = redisTemplate.opsForHash().get(statsKey, "viewCount");

        vo.setAttitudesCount(likeObj == null ? 0 : Integer.parseInt(likeObj.toString()));
        vo.setCommentsCount(commentObj == null ? 0 : Integer.parseInt(commentObj.toString()));
        vo.setRepostsCount(forwardObj == null ? 0 : Integer.parseInt(forwardObj.toString()));
        vo.setViewsCount(viewObj == null ? 0 : Integer.parseInt(viewObj.toString()));
    }
}
