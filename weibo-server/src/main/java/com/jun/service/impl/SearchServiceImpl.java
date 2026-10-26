package com.jun.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.context.BaseContext;
import com.jun.dto.SearchResultDTO;
import com.jun.dto.SearchUsersDTO;
import com.jun.entity.StatusImage;
import com.jun.enumeration.TargetType;
import com.jun.mapper.SearchMapper;
import com.jun.model.ScoredPost;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.SearchService;
import com.jun.service.RewardService;
import com.jun.utils.FollowSetHelper;
import com.jun.utils.HotScoreCalculator;
import com.jun.utils.LikeSetHelper;
import com.jun.utils.PostStatsHelper;
import com.jun.vo.*;
import jakarta.annotation.Resource;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.jun.constant.RedisConstants.*;

@Service
public class SearchServiceImpl implements SearchService {

    @Resource
    private SearchMapper searchMapper;
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
    public Result comprehensionResults(SearchResultDTO searchResultDTO) {
        Long currentUserId = BaseContext.getCurrentId();
        String keyword = searchResultDTO.getQ();
        Integer page = searchResultDTO.getPage();
        Integer pageSize = searchResultDTO.getPageSize();

        //默认只搜索31天，支持自定义时间区间
        LocalDateTime startDate;
        LocalDateTime endDate;
        String dateSuffix;
        if (searchResultDTO.getStartDate() != null && searchResultDTO.getEndDate() != null) {
            // 用户传递了自定义时间区间
            startDate = searchResultDTO.getStartDate().atStartOfDay();
            endDate = searchResultDTO.getEndDate().atTime(java.time.LocalTime.MAX);
            dateSuffix = startDate.toEpochSecond(ZoneOffset.UTC) + ":" + endDate.toEpochSecond(ZoneOffset.UTC);
        } else {
            // 默认31天
            endDate = LocalDateTime.now();
            startDate = endDate.minusDays(31);
            dateSuffix = "default";
        }

        //1. ID缓存（含时间区间标识，避免不同时间范围共用缓存）
        String idsCacheKey = SEARCH_IDS_COMPREHENSIVE_KEY + keyword + ":" + dateSuffix;
        List<Long> allIds = null;
        Object idsCacheJson = redisTemplate.opsForValue().get(idsCacheKey);

        if (idsCacheJson != null) {
            try {
                allIds = JSONUtil.parseArray(idsCacheJson).toList(Long.class);
            } catch (Exception e) {
                redisTemplate.delete(idsCacheKey);
            }
        }

        if (allIds == null) {
            // 缓存未命中 → 查数据库（最多500条，31天内）
            List<Long> recallIds = searchMapper.getComprehensiveResultIds(keyword, startDate, endDate);

            if (recallIds == null || recallIds.isEmpty()) {
                redisTemplate.opsForValue().set(idsCacheKey, JSONUtil.toJsonStr(Collections.emptyList()), 60, TimeUnit.SECONDS);
                return Result.success(new PageResult(0L, Collections.emptyList()));
            }

            allIds = recallIds;
            // 写入ID缓存（30分钟）
            redisTemplate.opsForValue().set(
                    idsCacheKey,
                    JSONUtil.toJsonStr(allIds),
                    SEARCH_RESULT_TTL + new Random().nextInt(300),
                    TimeUnit.SECONDS
            );
        }

        //2. 过滤已软删除的ID
        // 批量查 post:detail 缓存，不存在的说明可能已被软删除
        List<String> detailKeys = allIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> detailJsonList = redisTemplate.opsForValue().multiGet(detailKeys);

        List<Long> validIds = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < allIds.size(); i++) {
            Long id = allIds.get(i);
            String json = detailJsonList != null && i < detailJsonList.size() ? detailJsonList.get(i) : null;
            if (json != null && !json.isEmpty()) {
                validIds.add(id);
            } else {
                missIds.add(id);
            }
        }

        // miss 的 ID 查 DB 确认哪些还存在
        if (!missIds.isEmpty()) {
            List<SearchStatusVO> dbResults = searchMapper.getPostByIds(missIds);
            Set<Long> dbIdSet = dbResults.stream().map(SearchStatusVO::getId).collect(Collectors.toSet());
            for (Long id : missIds) {
                if (dbIdSet.contains(id)) {
                    validIds.add(id);
                }
            }
            // 有删掉的 ID → 更新缓存（去掉已删的）
            List<Long> deletedIds = missIds.stream().filter(id -> !dbIdSet.contains(id)).toList();
            if (!deletedIds.isEmpty()) {
                allIds.removeAll(deletedIds);
                redisTemplate.opsForValue().set(
                        idsCacheKey,
                        JSONUtil.toJsonStr(allIds),
                        SEARCH_RESULT_TTL + new Random().nextInt(300),
                        TimeUnit.SECONDS
                );
            }
        }

        if (validIds.isEmpty()) {
            return Result.success(new PageResult(0L, Collections.emptyList()));
        }

        //3. 获取帖子详情
        List<String> allDetailKeys = validIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> allDetailJsonList = redisTemplate.opsForValue().multiGet(allDetailKeys);

        List<SearchStatusVO> posts = new ArrayList<>();
        List<Long> detailMissIds = new ArrayList<>();
        // 刚才 miss->DB 查到的 ID（没有 post:detail 缓存，需要单独补）
        Set<Long> missButFound = new HashSet<>(missIds);
        missButFound.retainAll(validIds);

        for (int i = 0; i < validIds.size(); i++) {
            Long id = validIds.get(i);
            String json = allDetailJsonList != null && i < allDetailJsonList.size() ? allDetailJsonList.get(i) : null;

            if (json != null && !json.isEmpty() && !missButFound.contains(id)) {
                SearchStatusVO vo = JSONUtil.toBean(json, SearchStatusVO.class);
                if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() != null) {
                    vo.setOriginalStatus(null);
                }
                posts.add(vo);
            } else {
                detailMissIds.add(id);
            }
        }

        // 查 DB 补详情
        if (!detailMissIds.isEmpty()) {
            List<SearchStatusVO> dbList = searchMapper.getPostByIds(detailMissIds);
            List<Long> dbIdList = dbList.stream().map(SearchStatusVO::getId).toList();
            List<StatusImage> images = searchMapper.getImageByIds(dbIdList);
            Map<Long, List<StatusImage>> imageMap = images.stream()
                    .collect(Collectors.groupingBy(StatusImage::getStatusId));

            for (SearchStatusVO vo : dbList) {
                vo.setImages(imageMap.get(vo.getId()));
            }

            // 回写缓存
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (SearchStatusVO vo : dbList) {
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

            posts.addAll(dbList);
            fillOriginalStatus(dbList, currentUserId);
        }

        //4. 计算综合排序分
        fillDynamicData(posts, currentUserId);

        List<ScoredPost> scoredPosts = new ArrayList<>();
        for (SearchStatusVO vo : posts) {
            double keywordScore = 0;
            String content = vo.getContent();
            if (content != null && content.contains(keyword)) {
                keywordScore += 5;
            }
            int count = 0;
            int index = 0;
            if (content != null) {
                while ((index = content.indexOf(keyword, index)) != -1) {
                    count++;
                    index += keyword.length();
                }
            }
            keywordScore += Math.log(count + 1) * 3;

            long hours = ChronoUnit.HOURS.between(vo.getCreatedAt(), LocalDateTime.now());
            double timeScore = 1.0 / Math.pow(hours + 2, 0.3);

            double hotScore = HotScoreCalculator.calculateSearchHotScore(
                    vo.getViewsCount(), vo.getAttitudesCount(),
                    vo.getCommentsCount(), vo.getRepostsCount());

            double finalScore = keywordScore * 0.6 + timeScore * 10 * 0.25 + hotScore * 0.15;
            scoredPosts.add(new ScoredPost(vo, finalScore));
        }

        scoredPosts.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        //5. 分页
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, scoredPosts.size());
        List<SearchStatusVO> result = scoredPosts.subList(start, end).stream()
                .map(ScoredPost::getPost).toList();

        //6. 用户信息
        List<Long> userIds = result.stream().map(SearchStatusVO::getUserId).distinct().toList();
        List<UserVO> users = searchMapper.getUsersInfoByUserIds(userIds);
        Map<Long, UserVO> userMap = users.stream().collect(Collectors.toMap(UserVO::getId, v -> v));
        for (SearchStatusVO vo : result) {
            vo.setUser(userMap.get(vo.getUserId()));
        }

        // 填充转发原微博（仅用于本次响应）
        fillOriginalStatus(result, currentUserId);

        // 填充扩展信息（抽奖活动等）
        List<Long> allStatusIds = result.stream().map(SearchStatusVO::getId).toList();
        Map<Long, List<ExtensionVO>> extMap = rewardService.getExtensionsByStatusIds(allStatusIds);
        for (SearchStatusVO vo : result) {
            vo.setExtensions(extMap.getOrDefault(vo.getId(), Collections.emptyList()));
        }

        //total 取 allIds.size()（过滤前的总数）
        return Result.success(new PageResult((long) allIds.size(), result));
    }

    @Override
    public Result realtimeResults(SearchResultDTO searchResultDTO) {
        Long userId = BaseContext.getCurrentId();

        //1. 数据库分页查询 ids（直接 DB 分页，不做搜索缓存）
        String keyword = searchResultDTO.getQ();
        Integer page = searchResultDTO.getPage();
        Integer pageSize = searchResultDTO.getPageSize();

        searchResultDTO.setPage((page - 1) * pageSize);
        List<Long> statusIds = searchMapper.getResultIds(searchResultDTO);
        if (statusIds.isEmpty()) {
            Long total = searchMapper.countResults(keyword);
            return Result.success(new PageResult(total, Collections.emptyList()));
        }

        //2. 批量获取帖子详情（旁路缓存策略：multiGet post:detail → DB fallback）
        List<SearchStatusVO> posts = fetchPostDetails(statusIds, userId);

        if (posts.isEmpty()) {
            Long total = searchMapper.countResults(keyword);
            return Result.success(new PageResult(total, Collections.emptyList()));
        }

        //3. 用户信息组装
        List<Long> userIdList = posts.stream()
                .map(SearchStatusVO::getUserId)
                .distinct()
                .toList();

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

        if (!missUserIds.isEmpty()) {
            List<UserVO> dbUsers = searchMapper.getUsersInfoByUserIds(missUserIds);
            for (UserVO user : dbUsers) {
                userMap.put(user.getId(), user);
            }
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

        for (SearchStatusVO vo : posts) {
            vo.setUser(userMap.get(vo.getUserId()));
        }

        //4. 动态数据填充（实时点赞数/评论数/转发数）
        fillDynamicData(posts, userId);

        //5. 回写 post:detail 缓存（不含 originalStatus）
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (SearchStatusVO vo : posts) {
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

        //6. 填充转发原微博（仅用于本次响应）
        fillOriginalStatus(posts, userId);

        // 填充扩展信息（抽奖活动等）
        List<Long> allStatusIds = posts.stream().map(SearchStatusVO::getId).toList();
        Map<Long, List<ExtensionVO>> extMap = rewardService.getExtensionsByStatusIds(allStatusIds);
        for (SearchStatusVO vo : posts) {
            vo.setExtensions(extMap.getOrDefault(vo.getId(), Collections.emptyList()));
        }

        Long total = searchMapper.countResults(keyword);
        return Result.success(new PageResult(total, posts));
    }

    private void fillDynamicData(List<SearchStatusVO> list, Long userId) {

        if (list == null || list.isEmpty()) return;

        //1. 批量查询 stats（含原微博）
        for (SearchStatusVO vo : list) {
            fillPostStats(vo);
            if (vo.getOriginalStatus() != null) {
                fillPostStats(vo.getOriginalStatus());
            }
        }

        //2. 是否点赞（含原微博）
        if (userId != null) {

            List<String> likeKeys = new ArrayList<>();
            List<Long> likeTargetIds = new ArrayList<>();
            for (SearchStatusVO vo : list) {
                likeKeys.add(LIKE_POST_KEY + vo.getId());
                likeTargetIds.add(vo.getId());
                if (vo.getOriginalStatus() != null) {
                    likeKeys.add(LIKE_POST_KEY + vo.getOriginalStatus().getId());
                    likeTargetIds.add(vo.getOriginalStatus().getId());
                }
            }

            // 兜底：点赞集合缓存缺失时从数据库重建，防止已点赞显示为未点赞
            likeSetHelper.ensureLikeSets(TargetType.POST, likeTargetIds);

            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : likeKeys) {
                    connection.setCommands().sIsMember(
                            key.getBytes(),
                            userId.toString().getBytes()
                    );
                }
                return null;
            });

            int idx = 0;
            for (SearchStatusVO vo : list) {
                Boolean isMember = (Boolean) results.get(idx++);
                vo.setIsLiked(isMember != null && isMember);

                if (vo.getOriginalStatus() != null) {
                    Boolean origIsMember = (Boolean) results.get(idx++);
                    vo.getOriginalStatus().setIsLiked(origIsMember != null && origIsMember);
                }
            }

        } else {
            list.forEach(vo -> {
                vo.setIsLiked(false);
                if (vo.getOriginalStatus() != null) {
                    vo.getOriginalStatus().setIsLiked(false);
                }
            });
        }
    }

    private void fillPostStats(SearchStatusVO vo) {
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

    /**
     * 批量获取帖子详情（缓存 → DB fallback）
     */
    private List<SearchStatusVO> fetchPostDetails(List<Long> statusIds, Long currentUserId) {
        if (statusIds == null || statusIds.isEmpty()) return Collections.emptyList();

        List<String> detailKeys = statusIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(detailKeys);

        List<SearchStatusVO> posts = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < statusIds.size(); i++) {
            String json = jsonList != null && i < jsonList.size() ? jsonList.get(i) : null;
            Long id = statusIds.get(i);

            if (json != null && !json.isEmpty()) {
                SearchStatusVO vo = JSONUtil.toBean(json, SearchStatusVO.class);
                if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() != null) {
                    vo.setOriginalStatus(null);
                }
                posts.add(vo);
            } else {
                missIds.add(id);
            }
        }

        if (!missIds.isEmpty()) {
            List<SearchStatusVO> dbList = searchMapper.getPostByIds(missIds);
            if (!dbList.isEmpty()) {
                List<Long> dbIdList = dbList.stream().map(SearchStatusVO::getId).toList();
                List<StatusImage> images = searchMapper.getImageByIds(dbIdList);
                Map<Long, List<StatusImage>> imageMap = images.stream()
                        .collect(Collectors.groupingBy(StatusImage::getStatusId));

                for (SearchStatusVO vo : dbList) {
                    vo.setImages(imageMap.get(vo.getId()));
                }

                // 回写 post:detail 缓存
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (SearchStatusVO vo : dbList) {
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

                posts.addAll(dbList);
                fillOriginalStatus(dbList, currentUserId);
            }
        }

        return posts;
    }

    @Override
    public Result users(SearchUsersDTO searchUsersDTO) {
        PageHelper.startPage(searchUsersDTO.getPage(),searchUsersDTO.getPageSize());
        Page<SearchUserVO> page = searchMapper.getUsers(searchUsersDTO);
        List<SearchUserVO> records = page.getResult();

        Long currentUserId = BaseContext.getCurrentId();

        // 读路径兜底：关注集合过期后从 DB 重建，避免 isFollowed 全为 false
        followSetHelper.ensureFollowing(currentUserId);
        Set<Object> myFollowings = redisTemplate.opsForSet().members(FOLLOW_FOLLOWING_KEY + currentUserId);
        Set<Long> myFollowingsIds = myFollowings.stream()
                .map(id -> Long.valueOf(id.toString()))
                .collect(Collectors.toSet());

        for (SearchUserVO searchUserVO : page) {
            if (currentUserId == null || !currentUserId.equals(searchUserVO.getId())) {
                searchUserVO.setIsFollowed(myFollowingsIds.contains(searchUserVO.getId()));
            } else {
                searchUserVO.setIsFollowed(null);
            }
        }

        long total = page.getTotal();
        return Result.success(new PageResult(total,records));
    }

    private void fillOriginalStatus(List<SearchStatusVO> finalList, Long currentUserId) {

        //1. 收集 originalIds
        List<Long> originalIds = finalList.stream()
                .filter(vo -> vo.getOriginalStatusId() != null)
                .filter(vo -> vo.getOriginalStatus() == null)
                .map(SearchStatusVO::getOriginalStatusId)
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

        Map<Long, SearchStatusVO> originalMap = new HashMap<>();

        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < originalIds.size(); i++) {
            Long id = originalIds.get(i);

            String json = jsonList != null ? jsonList.get(i) : null;

            if (json != null && !json.isEmpty()) {
                SearchStatusVO vo = JSONUtil.toBean(json, SearchStatusVO.class);
                originalMap.put(id, vo);
            } else {
                missIds.add(id);
            }
        }

        //3. miss -> DB
        if (!missIds.isEmpty()) {
            List<SearchStatusVO> dbList = searchMapper.getPostByOriginalIds(missIds);

            if (!dbList.isEmpty()) {

                //3.1 图片
                List<StatusImage> images = searchMapper.getImageByIds(missIds);

                Map<Long, List<StatusImage>> imageMap = images.stream()
                        .collect(Collectors.groupingBy(StatusImage::getStatusId));

                //3.2 用户
                List<Long> userIds = dbList.stream()
                        .map(SearchStatusVO::getUserId)
                        .distinct()
                        .toList();

                List<UserVO> users = searchMapper.getUsersInfoByUserIds(userIds);

                Map<Long, UserVO> userMap = users.stream().collect(Collectors.toMap(UserVO::getId, v -> v));

                for (SearchStatusVO vo : dbList) {
                    //图片
                    vo.setImages(imageMap.get(vo.getId()));
                    //用户
                    vo.setUser(userMap.get(vo.getUserId()));
                    originalMap.put(vo.getId(), vo);
                }

                //3.3 回写缓存
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (SearchStatusVO vo : dbList) {
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

        //4. 批量填充实时统计（miss 时从数据库重建）
        List<Map<Object, Object>> statsList = postStatsHelper.getStatsBatch(originalIds);

        for (int i = 0; i < originalIds.size(); i++) {

            Long id = originalIds.get(i);

            SearchStatusVO vo = originalMap.get(id);

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

                SearchStatusVO vo = originalMap.get(originalIds.get(i));

                if (vo == null) {
                    continue;
                }

                Boolean liked = (Boolean) likeResults.get(i);

                vo.setIsLiked(liked != null && liked);
            }

        } else {

            originalMap.values().forEach(vo -> vo.setIsLiked(false));
        }

        //6. 回填 originalStatus（只填充 originalStatus 为 null 的，避免覆盖已有的）
        for (SearchStatusVO vo : finalList) {

            if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() == null) {

                vo.setOriginalStatus(originalMap.get(vo.getOriginalStatusId()));
            }
        }
    }
}
