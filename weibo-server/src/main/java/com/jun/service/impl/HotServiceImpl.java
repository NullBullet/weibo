package com.jun.service.impl;

import cn.hutool.json.JSONUtil;
import com.jun.dto.ScrollDTO;
import com.jun.entity.StatusImage;
import com.jun.enumeration.TargetType;
import com.jun.result.Result;
import com.jun.result.ScrollResult;
import com.jun.service.RewardService;
import com.jun.vo.ExtensionVO;
import com.jun.vo.HotStatusVO;
import com.jun.vo.HotTopicVO;
import com.jun.vo.StatusVO;
import com.jun.mapper.HotMapper;
import com.jun.service.HotService;
import com.jun.utils.FollowSetHelper;
import com.jun.utils.LikeSetHelper;
import com.jun.utils.PostStatsHelper;
import com.jun.vo.UserVO;
import jakarta.annotation.Resource;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.jun.constant.RedisConstants.*;

@Service
public class HotServiceImpl implements HotService {

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

    @Override
    public Result<List<HotTopicVO>> topics(Integer size) {
        List<HotTopicVO> listTopics = hotMapper.getTopics(size);
        return Result.success(listTopics);
    }

    @Override
    public Result<ScrollResult> getPosts(ScrollDTO scrollDTO, Long currentUserId) {
        //1. 分页参数处理
        Double lastScore = scrollDTO.getLastScore();
        Integer pageSize = scrollDTO.getPageSize();
        Integer offset = scrollDTO.getOffset();
        if (pageSize == null || pageSize <= 0) {
            pageSize = 10;
        }

        if (offset == null) {
            offset = 0;
        }
        if (lastScore == null) {
            lastScore = Double.POSITIVE_INFINITY;
        }

        //2. 从 ZSET 获取 statusIds
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(
                                HOT_POST_ZSET_KEY,
                                Double.NEGATIVE_INFINITY,
                                lastScore,
                                offset,
                                pageSize
                        );
        if (tuples == null || tuples.isEmpty()) {
            return Result.success(new ScrollResult(null, 0, Collections.emptyList()));
        }

        //3. 批量从缓存获取帖子详情
        List<Long> statusIds = tuples.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .toList();

        List<String> keys = statusIds.stream().map(id -> POST_DETAIL_KEY + id).toList();

        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);

        //4. 找缺失的帖子 ID（缓存未命中）
        List<HotStatusVO> result = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < statusIds.size(); i++) {
            String json = jsonList.get(i);
            Long id = statusIds.get(i);

            if (json!=null&&!json.isEmpty()) {
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

        //5. 批量查询数据库
        if (!missIds.isEmpty()) {

            List<HotStatusVO> dbList = hotMapper.getPostByMissIds(missIds);

            List<Long> missIdList = dbList.stream().map(HotStatusVO::getId).toList();
            List<StatusImage> images = hotMapper.getImageByIds(missIdList);

            Map<Long, List<StatusImage>> imageMap = images.stream()
                    .collect(Collectors.groupingBy(StatusImage::getStatusId));

            for (HotStatusVO vo : dbList) {
                vo.setImages(imageMap.get(vo.getId()));
            }

            //6. 先 pipeline 回写缓存（不含 originalStatus，避免嵌套缓存过时）
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

                for (HotStatusVO vo : dbList) {
                    String key = POST_DETAIL_KEY + vo.getId();
                    String value = JSONUtil.toJsonStr(vo);

                    long expireTime = HOT_POST_DETAIL_TTL + new Random().nextInt(3600);
                    connection.stringCommands().set(
                            key.getBytes(),
                            value.getBytes(),
                            Expiration.seconds(expireTime),
                            RedisStringCommands.SetOption.UPSERT
                    );
                }
                //要求回调函数返回 null
                return null;
            });

            //再填充转发原微博（仅用于本次响应，不写缓存）
            // 不在此处调用 fillOriginalStatus，统一在 finalList 构建完成后处理
            // 避免 dbList 带上 originalStatus 后被写入缓存
            result.addAll(dbList);
        }

        //7. 以微博id分类缓存
        Map<Long, HotStatusVO> map = result.stream()
                .collect(Collectors.toMap(HotStatusVO::getId, v -> v));

        //8. 用户信息缓存
        //8.1 收集 userId（去重）
        List<Long> userIdList = statusIds.stream()
                .map(id -> map.get(id).getUserId())
                .distinct()
                .toList();

        //8.2 批量查 Redis
        List<String> userKeys = userIdList.stream()
                .map(uid -> USER_INFO_KEY + uid)
                .toList();
        List<String> userJsonList = redisTemplate.opsForValue().multiGet(userKeys);

        Map<Long, UserVO> userMap = new HashMap<>();
        List<Long> missUserIds = new ArrayList<>();

        //8.3 解析缓存 + 找 miss
        for (int i = 0; i < userIdList.size(); i++) {
            Long uid = userIdList.get(i);
            String json = (userJsonList != null) ? userJsonList.get(i) : null;

            if (json != null && !json.isEmpty()) {
                userMap.put(uid, JSONUtil.toBean(json, UserVO.class));
            } else {
                missUserIds.add(uid);
            }
        }

        //8.4 回源 DB
        if (!missUserIds.isEmpty()) {

            List<UserVO> dbUsers = hotMapper.getUsersInfoByUserIds(missUserIds);

            for (UserVO user : dbUsers) {
                userMap.put(user.getId(), user);
            }

            //8.5 回写 Redis（pipeline）
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

        //9. 获取关注列表（读路径兜底：集合过期后从 DB 重建，避免 isFollowed 全为 false）
        followSetHelper.ensureFollowing(currentUserId);
        Set<Object> myFollowings = redisTemplate.opsForSet().members(FOLLOW_FOLLOWING_KEY + currentUserId);
        Set<Long> myFollowingsIds = myFollowings.stream()
                .map(id -> Long.valueOf(id.toString()))
                .collect(Collectors.toSet());

        //10. 构建 finalList
        List<HotStatusVO> finalList = new ArrayList<>();

        for (Long id : statusIds) {
            HotStatusVO vo = map.get(id);
            if (vo == null) continue;

            //填充用户信息
            vo.setUser(userMap.get(vo.getUserId()));

            finalList.add(vo);
            //9.1 判断是否关注
            if (currentUserId == null || !currentUserId.equals(vo.getUserId())) {
                vo.setIsFollowed(myFollowingsIds.contains(vo.getUserId()));
            } else {
                vo.setIsFollowed(null);
            }
        }

        // 填充扩展信息（抽奖活动等），在写缓存前完成，确保缓存包含 extensions
        List<Long> allStatusIds = finalList.stream().map(HotStatusVO::getId).toList();
        Map<Long, List<ExtensionVO>> extMap = rewardService.getExtensionsByStatusIds(allStatusIds);
        for (HotStatusVO vo : finalList) {
            vo.setExtensions(extMap.getOrDefault(vo.getId(), Collections.emptyList()));
        }

        // 先回写缓存（不含 originalStatus，避免嵌套缓存过时）
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

            for (HotStatusVO vo : finalList) {

                String cacheKey = POST_DETAIL_KEY + vo.getId();

                String cacheValue = JSONUtil.toJsonStr(vo);

                long expireTime =
                        HOT_POST_DETAIL_TTL + new Random().nextInt(3600);

                connection.stringCommands().set(
                        cacheKey.getBytes(),
                        cacheValue.getBytes(),
                        Expiration.seconds(expireTime),
                        RedisStringCommands.SetOption.UPSERT
                );
            }

            return null;
        });

        // 再填充转发原微博（仅用于本次响应，不写缓存）
        fillOriginalStatus(finalList, currentUserId);

        //11. 获取即时统计数据（含原微博统计）
        for (HotStatusVO vo : finalList) {
            fillPostStats(vo);

            // 更新原微博实时统计
            HotStatusVO originalStatus = vo.getOriginalStatus();
            if (originalStatus != null) {
                fillPostStats(originalStatus);
            }
        }

        //12. 当前用户是否点赞（含原微博）
        if (currentUserId != null) {
            //12.1 收集所有微博的 LikeKey（含原微博），用于批量检查
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

            //12.2 兜底：点赞集合缓存缺失时从数据库重建，防止已点赞显示为未点赞
            likeSetHelper.ensureLikeSets(TargetType.POST, likeTargetIds);

            //12.3 批量检查
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : likeKeys) {
                    connection.setCommands().sIsMember(key.getBytes(), currentUserId.toString().getBytes());
                }
                return null;
            });

            //12.4 回填
            int idx = 0;
            for (HotStatusVO vo : finalList) {
                Boolean isMember = (Boolean) results.get(idx++);
                vo.setIsLiked(isMember != null && isMember);

                if (vo.getOriginalStatus() != null) {
                    Boolean origIsMember = (Boolean) results.get(idx++);
                    vo.getOriginalStatus().setIsLiked(origIsMember != null && origIsMember);
                }
            }
        } else {
            finalList.forEach(vo -> {
                vo.setIsLiked(false);
                if (vo.getOriginalStatus() != null) {
                    vo.getOriginalStatus().setIsLiked(false);
                }
            });
        }

        //13. 计算下一页游标
        double minScore = tuples.stream()
                .mapToDouble(ZSetOperations.TypedTuple::getScore)
                .min()
                .orElse(0);

        int newOffset = 0;

        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getScore() == minScore) {
                newOffset++;
            }
        }

        //14. 返回
        return Result.success(new ScrollResult(minScore, newOffset, finalList));
    }

    private void fillOriginalStatus(List<HotStatusVO> finalList, Long currentUserId) {

        //1. 收集 originalIds
        List<Long> originalIds = finalList.stream()
                .filter(vo -> vo.getOriginalStatusId() != null)
                .filter(vo -> vo.getOriginalStatus() == null)
                .map(HotStatusVO::getOriginalStatusId)
                .distinct()
                .toList();

        if (originalIds.isEmpty()) {
            return;
        }

        //2. 先查 Redis
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

        //3. miss -> DB
        if (!missIds.isEmpty()) {
            List<HotStatusVO> dbList = hotMapper.getPostByIds(missIds);

            if (!dbList.isEmpty()) {

                //3.1 图片
                List<StatusImage> images = hotMapper.getImageByIds(missIds);

                Map<Long, List<StatusImage>> imageMap = images.stream()
                        .collect(Collectors.groupingBy(StatusImage::getStatusId));

                //3.2 用户
                List<Long> userIds = dbList.stream()
                        .map(HotStatusVO::getUserId)
                        .distinct()
                        .toList();

                List<UserVO> users = hotMapper.getUsersInfoByUserIds(userIds);

                Map<Long, UserVO> userMap = users.stream().collect(Collectors.toMap(UserVO::getId, v -> v));

                for (HotStatusVO vo : dbList) {
                    //图片
                    vo.setImages(imageMap.get(vo.getId()));
                    //用户
                    vo.setUser(userMap.get(vo.getUserId()));
                    originalMap.put(vo.getId(), vo);
                }

                //3.3 填充扩展信息（抽奖活动等），在写缓存前完成
                List<Long> origIds = new ArrayList<>(originalMap.keySet());
                if (!origIds.isEmpty()) {
                    Map<Long, List<ExtensionVO>> origExtMap = rewardService.getExtensionsByStatusIds(origIds);
                    for (HotStatusVO vo : originalMap.values()) {
                        vo.setExtensions(origExtMap.getOrDefault(vo.getId(), Collections.emptyList()));
                    }
                }

                //3.4 回写缓存
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (HotStatusVO vo : dbList) {
                        String key = POST_DETAIL_KEY + vo.getId();
                        String value = JSONUtil.toJsonStr(vo);
                        long expire = HOT_POST_DETAIL_TTL + new Random().nextInt(3600);
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

        //4. 批量填充实时统计（miss 时从数据库重建）
        List<Map<Object, Object>> statsList = postStatsHelper.getStatsBatch(originalIds);

        for (int i = 0; i < originalIds.size(); i++) {

            Long id = originalIds.get(i);

            HotStatusVO vo = originalMap.get(id);

            if (vo == null) {
                continue;
            }

            Map<Object, Object> stats = statsList.get(i);

            if (stats != null) {

                vo.setAttitudesCount(PostStatsHelper.getInt(stats, "likeCount"));

                vo.setCommentsCount(PostStatsHelper.getInt(stats, "commentCount"));

                vo.setRepostsCount(PostStatsHelper.getInt(stats, "forwardCount"));

                vo.setViewsCount(PostStatsHelper.getInt(stats, "viewCount"));
            }
        }

        //5. 批量判断点赞
        if (currentUserId != null) {
            // 兜底：点赞集合缓存缺失时从数据库重建，防止已点赞显示为未点赞
            likeSetHelper.ensureLikeSets(TargetType.POST, originalIds);

            List<Object> likeResults =
                    redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
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

                if (vo == null) {
                    continue;
                }

                Boolean liked = (Boolean) likeResults.get(i);

                vo.setIsLiked(liked != null && liked);
            }

        } else {

            originalMap.values().forEach(vo -> vo.setIsLiked(false));
        }

        //6. 填充扩展信息（抽奖活动等）
        if (!originalMap.isEmpty()) {
            Map<Long, List<ExtensionVO>> origExtMap = rewardService.getExtensionsByStatusIds(
                    new ArrayList<>(originalMap.keySet()));
            for (HotStatusVO vo : originalMap.values()) {
                vo.setExtensions(origExtMap.getOrDefault(vo.getId(), Collections.emptyList()));
            }
        }

        //7. 回填 originalStatus（只填充 originalStatus 为 null 的，避免覆盖已有的）
        for (HotStatusVO vo : finalList) {

            if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() == null) {

                vo.setOriginalStatus(originalMap.get(vo.getOriginalStatusId()));
            }
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
