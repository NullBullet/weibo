package com.jun.service.impl;

import cn.hutool.json.JSONUtil;
import com.jun.context.BaseContext;
import com.jun.dto.HomeQueryDTO;
import com.jun.entity.StatusImage;
import com.jun.enumeration.TargetType;
import com.jun.enumeration.TimelineType;
import com.jun.mapper.HotMapper;
import com.jun.result.Result;
import com.jun.result.ScrollResult;
import com.jun.service.HomeService;
import com.jun.service.RewardService;
import com.jun.utils.FeedHelper;
import com.jun.utils.FollowSetHelper;
import com.jun.utils.LikeSetHelper;
import com.jun.utils.PostStatsHelper;
import com.jun.vo.ExtensionVO;
import com.jun.vo.HotStatusVO;
import com.jun.vo.UserVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.DefaultTypedTuple;
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
    @Resource
    private RewardService rewardService;
    @Resource
    private PostStatsHelper postStatsHelper;
    @Resource
    private LikeSetHelper likeSetHelper;
    @Resource
    private FollowSetHelper followSetHelper;
    @Resource
    private FeedHelper feedHelper;

    @Override
    public Result getLatestPosts(HomeQueryDTO homeQueryDTO) {
        Long userId = BaseContext.getCurrentId();
        Double lastScore = homeQueryDTO.getLastScore();
        Integer pageSize = homeQueryDTO.getPageSize();
        Integer offset = homeQueryDTO.getOffset();

        if (pageSize == null || pageSize <= 0) pageSize = 10;
        if (offset == null) offset = 0;
        if (lastScore == null) lastScore = Double.POSITIVE_INFINITY;

        LocalDate startDate = homeQueryDTO.getStartDate();
        LocalDate endDate = homeQueryDTO.getEndDate();
        double minScore = Double.NEGATIVE_INFINITY;
        if (startDate != null) {
            minScore = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        }
        double maxScore = lastScore;
        if (endDate != null) {
            double endOfDay = endDate.atTime(LocalTime.MAX).toEpochSecond(ZoneOffset.UTC);
            maxScore = Math.min(lastScore, endOfDay);
        }

        String feedKey = FEED_USER_KEY + userId;
        // 读路径兜底：收件箱过期/丢失时按关注关系从 DB 重建，避免首页静默为空
        feedHelper.ensureFeed(userId);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(feedKey, minScore, maxScore, offset, pageSize);

        if (tuples == null || tuples.isEmpty()) {
            return Result.success(new ScrollResult(null, 0, Collections.emptyList()));
        }

        // 访问命中，续期收件箱 TTL
        feedHelper.refreshTtl(userId);

        List<Long> statusIds = tuples.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .toList();

        return buildResult(userId, statusIds, tuples);
    }

    @Override
    public Result getHotPosts(HomeQueryDTO homeQueryDTO) {
        Long userId = BaseContext.getCurrentId();
        Integer pageSize = homeQueryDTO.getPageSize();
        Double lastScore = homeQueryDTO.getLastScore();
        Integer offset = homeQueryDTO.getOffset();

        if (pageSize == null || pageSize <= 0) pageSize = 10;
        if (offset == null) offset = 0;

        String feedKey = FEED_USER_KEY + userId;
        // 读路径兜底：收件箱过期/丢失时按关注关系从 DB 重建
        feedHelper.ensureFeed(userId);
        Set<ZSetOperations.TypedTuple<String>> feedTuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(feedKey, 0, 999);

        if (feedTuples == null || feedTuples.isEmpty()) {
            return Result.success(new ScrollResult(null, 0, Collections.emptyList()));
        }

        // 访问命中，续期收件箱 TTL
        feedHelper.refreshTtl(userId);

        List<Long> feedPostIds = feedTuples.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .toList();

        List<Object> hotScores = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long pid : feedPostIds) {
                connection.zSetCommands().zScore(HOT_POST_ZSET_KEY.getBytes(), pid.toString().getBytes());
            }
            return null;
        });

        // 分离热门和非热门帖子
        List<UnifiedPost> hotPosts = new ArrayList<>();
        List<UnifiedPost> nonHotPosts = new ArrayList<>();
        for (int i = 0; i < feedPostIds.size(); i++) {
            Long pid = feedPostIds.get(i);
            double createTime = feedTuples.stream()
                    .filter(t -> t.getValue().equals(pid.toString()))
                    .findFirst().map(ZSetOperations.TypedTuple::getScore).orElse(0.0);
            Object hotObj = i < hotScores.size() ? hotScores.get(i) : null;
            if (hotObj instanceof Number hotNum) {
                hotPosts.add(new UnifiedPost(pid, hotNum.doubleValue(), createTime));
            } else {
                nonHotPosts.add(new UnifiedPost(pid, createTime, createTime));
            }
        }
        // 热门按热度降序，非热门按时间降序
        hotPosts.sort((a, b) -> Double.compare(b.unifiedScore, a.unifiedScore));
        nonHotPosts.sort((a, b) -> Double.compare(b.unifiedScore, a.unifiedScore));

        // 拼接：热门在前，非热门在后
        List<UnifiedPost> all = new ArrayList<>(hotPosts);
        all.addAll(nonHotPosts);

        // 游标分页
        List<UnifiedPost> paged;
        if (lastScore != null) {
            int skip = 0;
            int startIdx = -1;
            for (int i = 0; i < all.size(); i++) {
                UnifiedPost u = all.get(i);
                if (u.unifiedScore < lastScore) { startIdx = i; break; }
                else if (u.unifiedScore == lastScore) {
                    if (skip < offset) { skip++; continue; }
                    startIdx = i; break;
                }
            }
            if (startIdx < 0) {
                paged = Collections.emptyList();
            } else {
                paged = all.subList(startIdx, Math.min(startIdx + pageSize, all.size()));
            }
        } else {
            paged = all.stream().skip(offset).limit(pageSize).toList();
        }

        if (paged.isEmpty()) return Result.success(new ScrollResult(null, 0, Collections.emptyList()));

        List<Long> statusIds = paged.stream().map(UnifiedPost::id).toList();
        Set<ZSetOperations.TypedTuple<String>> fakeTuples = new LinkedHashSet<>();
        for (UnifiedPost up : paged) {
            fakeTuples.add(new DefaultTypedTuple<>(up.id.toString(), up.unifiedScore));
        }

        ScrollResult scrollResult = (ScrollResult) buildResult(userId, statusIds, fakeTuples).getData();
        if (scrollResult == null) return Result.success(new ScrollResult(null, 0, Collections.emptyList()));

        double minUnifiedScore = paged.get(paged.size() - 1).unifiedScore;
        int backwardCount = 0;
        for (int i = paged.size() - 1; i >= 0; i--) {
            if (paged.get(i).unifiedScore == minUnifiedScore) backwardCount++;
            else break;
        }

        return Result.success(new ScrollResult(minUnifiedScore, backwardCount, scrollResult.getRecords()));
    }

    // ======================== 通用构建逻辑 ========================

    /**
     * 根据 statusIds 从缓存/DB 获取帖子详情，构建返回结果
     */
    private Result buildResult(Long userId, List<Long> statusIds,
                                Set<ZSetOperations.TypedTuple<String>> tuples) {
        // 1. 批量从缓存获取帖子详情
        List<String> keys = statusIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);

        // 2. 找缺失的帖子
        List<HotStatusVO> result = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < statusIds.size(); i++) {
            String json = jsonList.get(i);
            Long id = statusIds.get(i);
            if (json != null && !json.isEmpty()) {
                HotStatusVO vo = JSONUtil.toBean(json, HotStatusVO.class);
                // 缓存中不存嵌套，读时强制重新查找原微博
                if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() != null) {
                    vo.setOriginalStatus(null);
                }
                result.add(vo);
            } else {
                missIds.add(id);
            }
        }

        // 3. 批量查询数据库（缓存缺失）
        if (!missIds.isEmpty()) {
            List<HotStatusVO> dbList = hotMapper.getPostByMissIds(missIds);
            if (!dbList.isEmpty()) {
                List<Long> missIdList = dbList.stream().map(HotStatusVO::getId).toList();
                List<StatusImage> images = hotMapper.getImageByIds(missIdList);
                Map<Long, List<StatusImage>> imageMap = images.stream()
                        .collect(Collectors.groupingBy(StatusImage::getStatusId));

                for (HotStatusVO vo : dbList) {
                    vo.setImages(imageMap.get(vo.getId()));
                }

                // 先回写缓存（不含 originalStatus，避免嵌套缓存过时）
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (HotStatusVO vo : dbList) {
                        String key = POST_DETAIL_KEY + vo.getId();
                        String value = JSONUtil.toJsonStr(vo);
                        long expireTime = POST_DETAIL_TTL + new Random().nextInt(300);
                        connection.stringCommands().set(
                                key.getBytes(), value.getBytes(),
                                Expiration.seconds(expireTime),
                                RedisStringCommands.SetOption.UPSERT
                        );
                    }
                    return null;
                });

                // 再填充原微博（仅用于本次响应，不写缓存）
                fillOriginalStatus(dbList, userId);
                result.addAll(dbList);
            }

            // 惰性清理：DB 未返回的 miss ID（已软删除）从 feed 中移除
            Set<Long> dbFoundIds = dbList.stream().map(HotStatusVO::getId).collect(Collectors.toSet());
            List<Long> deletedFeedIds = missIds.stream()
                    .filter(id -> !dbFoundIds.contains(id))
                    .toList();
            if (!deletedFeedIds.isEmpty()) {
                String feedKey = FEED_USER_KEY + userId;
                for (Long deletedId : deletedFeedIds) {
                    redisTemplate.opsForZSet().remove(feedKey, deletedId.toString());
                }
                log.info("惰性清理 feed 中已删除的帖子: userId={}, count={}", userId, deletedFeedIds.size());
            }
        }

        // 4. 按传入顺序重排
        Map<Long, HotStatusVO> map = result.stream()
                .collect(Collectors.toMap(HotStatusVO::getId, v -> v));

        // 5. 用户信息
        List<Long> userIdList = statusIds.stream()
                .map(id -> map.get(id).getUserId())
                .distinct()
                .toList();

        List<String> userKeys = userIdList.stream().map(uid -> USER_INFO_KEY + uid).toList();
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

        if (!missUserIds.isEmpty()) {
            List<UserVO> dbUsers = hotMapper.getUsersInfoByUserIds(missUserIds);
            for (UserVO user : dbUsers) userMap.put(user.getId(), user);

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (UserVO user : dbUsers) {
                    String key = USER_INFO_KEY + user.getId();
                    String value = JSONUtil.toJsonStr(user);
                    long expire = USER_INFO_TTL + new Random().nextInt(3600);
                    connection.stringCommands().set(
                            key.getBytes(), value.getBytes(),
                            Expiration.seconds(expire),
                            RedisStringCommands.SetOption.UPSERT
                    );
                }
                return null;
            });
        }

        // 6. 关注列表（读路径兜底：集合过期后从 DB 重建，避免 isFollowed 全为 false）
        followSetHelper.ensureFollowing(userId);
        Set<Object> myFollowings = redisTemplate.opsForSet().members(FOLLOW_FOLLOWING_KEY + userId);
        Set<Long> myFollowingsIds = myFollowings != null
                ? myFollowings.stream().map(id -> Long.valueOf(id.toString())).collect(Collectors.toSet())
                : Collections.emptySet();

        // 7. 构建 finalList
        List<HotStatusVO> finalList = new ArrayList<>();
        for (Long id : statusIds) {
            HotStatusVO vo = map.get(id);
            if (vo == null) continue;
            vo.setUser(userMap.get(vo.getUserId()));
            if (userId == null || !userId.equals(vo.getUserId())) {
                vo.setIsFollowed(myFollowingsIds.contains(vo.getUserId()));
            } else {
                vo.setIsFollowed(null);
            }
            finalList.add(vo);
        }

        // 8. 先回写缓存（不含 originalStatus，避免嵌套缓存过时）
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (HotStatusVO vo : finalList) {
                String cacheKey = POST_DETAIL_KEY + vo.getId();
                String cacheValue = JSONUtil.toJsonStr(vo);
                long expireTime = POST_DETAIL_TTL + new Random().nextInt(300);
                connection.stringCommands().set(
                        cacheKey.getBytes(), cacheValue.getBytes(),
                        Expiration.seconds(expireTime),
                        RedisStringCommands.SetOption.UPSERT
                );
            }
            return null;
        });

        // 再填充原微博（仅用于本次响应，不写缓存）
        fillOriginalStatus(finalList, userId);

        // 9. 即时统计数据
        for (HotStatusVO vo : finalList) {
            fillPostStats(vo);
            if (vo.getOriginalStatus() != null) fillPostStats(vo.getOriginalStatus());
        }

        // 10. 点赞状态
        List<String> likeKeys = new ArrayList<>();
        List<Long> likeTargetIds = new ArrayList<>();
        for (HotStatusVO vo : finalList) {
            likeKeys.add(LIKE_POST_KEY + vo.getId());
            likeTargetIds.add(vo.getId());
            if (vo.getOriginalStatus() != null) {
                likeKeys.add(LIKE_POST_KEY + vo.getOriginalStatus().getId());
                likeTargetIds.add(vo.getOriginalStatus().getId());
            }
        }
        // 兜底：点赞集合缓存缺失时从数据库重建，防止已点赞显示为未点赞
        likeSetHelper.ensureLikeSets(TargetType.POST, likeTargetIds);
        List<Object> likeResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : likeKeys)
                connection.setCommands().sIsMember(key.getBytes(), userId.toString().getBytes());
            return null;
        });
        int idx = 0;
        for (HotStatusVO vo : finalList) {
            Boolean isMember = (Boolean) likeResults.get(idx++);
            vo.setIsLiked(isMember != null && isMember);
            if (vo.getOriginalStatus() != null) {
                Boolean origIsMember = (Boolean) likeResults.get(idx++);
                vo.getOriginalStatus().setIsLiked(origIsMember != null && origIsMember);
            }
        }

        // 11. 游标
        double minScore = tuples.stream()
                .mapToDouble(ZSetOperations.TypedTuple::getScore)
                .min()
                .orElse(0);
        int newOffset = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getScore() == minScore) newOffset++;
        }

        // 填充扩展信息（抽奖活动等）
        List<Long> allStatusIds = finalList.stream().map(HotStatusVO::getId).toList();
        Map<Long, List<ExtensionVO>> extMap = rewardService.getExtensionsByStatusIds(allStatusIds);
        for (HotStatusVO vo : finalList) {
            vo.setExtensions(extMap.getOrDefault(vo.getId(), Collections.emptyList()));
        }

        return Result.success(new ScrollResult(minScore, newOffset, finalList));
    }

    // ======================== 记录类型 ========================

    private record UnifiedPost(Long id, double unifiedScore, double createTime) {}

    // ======================== 以下为私有辅助方法 ========================

    private void fillOriginalStatus(List<HotStatusVO> finalList, Long currentUserId) {
        List<Long> originalIds = finalList.stream()
                .filter(vo -> vo.getOriginalStatusId() != null)
                .filter(vo -> vo.getOriginalStatus() == null)
                .map(HotStatusVO::getOriginalStatusId)
                .distinct()
                .toList();
        if (originalIds.isEmpty()) return;

        List<String> cacheKeys = originalIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(cacheKeys);
        Map<Long, HotStatusVO> originalMap = new HashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < originalIds.size(); i++) {
            Long id = originalIds.get(i);
            String json = jsonList != null ? jsonList.get(i) : null;
            if (json != null && !json.isEmpty()) {
                originalMap.put(id, JSONUtil.toBean(json, HotStatusVO.class));
            } else {
                missIds.add(id);
            }
        }

        if (!missIds.isEmpty()) {
            List<HotStatusVO> dbList = hotMapper.getPostByIds(missIds);
            if (!dbList.isEmpty()) {
                List<StatusImage> images = hotMapper.getImageByIds(missIds);
                Map<Long, List<StatusImage>> imageMap = images.stream()
                        .collect(Collectors.groupingBy(StatusImage::getStatusId));
                List<Long> userIds = dbList.stream().map(HotStatusVO::getUserId).distinct().toList();
                List<UserVO> users = hotMapper.getUsersInfoByUserIds(userIds);
                Map<Long, UserVO> userMap = users.stream().collect(Collectors.toMap(UserVO::getId, v -> v));
                for (HotStatusVO vo : dbList) {
                    vo.setImages(imageMap.get(vo.getId()));
                    vo.setUser(userMap.get(vo.getUserId()));
                    originalMap.put(vo.getId(), vo);
                }
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (HotStatusVO vo : dbList) {
                        String key = POST_DETAIL_KEY + vo.getId();
                        String value = JSONUtil.toJsonStr(vo);
                        long expire = POST_DETAIL_TTL + new Random().nextInt(300);
                        connection.stringCommands().set(
                                key.getBytes(), value.getBytes(),
                                Expiration.seconds(expire),
                                RedisStringCommands.SetOption.UPSERT
                        );
                    }
                    return null;
                });
            }
        }

        //4. 批量填充实时统计（miss 时从数据库重建）
        List<Map<Object, Object>> statsList = postStatsHelper.getStatsBatch(originalIds);
        for (int i = 0; i < originalIds.size(); i++) {
            Long id = originalIds.get(i);
            HotStatusVO vo = originalMap.get(id);
            if (vo == null) continue;
            Map<Object, Object> stats = statsList.get(i);
            if (stats != null) {
                vo.setAttitudesCount(PostStatsHelper.getInt(stats, "likeCount"));
                vo.setCommentsCount(PostStatsHelper.getInt(stats, "commentCount"));
                vo.setRepostsCount(PostStatsHelper.getInt(stats, "forwardCount"));
                vo.setViewsCount(PostStatsHelper.getInt(stats, "viewCount"));
            }
        }

        if (currentUserId != null) {
            // 兜底：点赞集合缓存缺失时从数据库重建，防止已点赞显示为未点赞
            likeSetHelper.ensureLikeSets(TargetType.POST, originalIds);
            List<Object> likeResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long id : originalIds)
                    connection.setCommands().sIsMember(
                            (LIKE_POST_KEY + id).getBytes(), currentUserId.toString().getBytes());
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

        for (HotStatusVO vo : finalList) {
            if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() == null)
                vo.setOriginalStatus(originalMap.get(vo.getOriginalStatusId()));
        }
    }

    private void fillPostStats(HotStatusVO vo) {
        Map<Object, Object> stats = postStatsHelper.getStats(vo.getId());
        if (stats == null) {
            vo.setAttitudesCount(0);
            vo.setCommentsCount(0);
            vo.setRepostsCount(0);
            vo.setViewsCount(0);
            return;
        }
        vo.setAttitudesCount(PostStatsHelper.getInt(stats, "likeCount"));
        vo.setCommentsCount(PostStatsHelper.getInt(stats, "commentCount"));
        vo.setRepostsCount(PostStatsHelper.getInt(stats, "forwardCount"));
        vo.setViewsCount(PostStatsHelper.getInt(stats, "viewCount"));
    }
}
