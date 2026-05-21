package com.jun.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.context.BaseContext;
import com.jun.dto.SearchResultDTO;
import com.jun.dto.SearchUsersDTO;
import com.jun.entity.StatusImage;
import com.jun.mapper.SearchMapper;
import com.jun.model.ScoredPost;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.SearchService;
import com.jun.utils.HotScoreCalculator;
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

    @Override
    public Result comprehensionResults(SearchResultDTO searchResultDTO) {
        //1. 缓存查询
        Long currentUserId = BaseContext.getCurrentId();
        String keyword = searchResultDTO.getQ();
        Integer page = searchResultDTO.getPage();
        Integer pageSize = searchResultDTO.getPageSize();

        //默认只搜索31天
        LocalDateTime endDate = null;
        LocalDateTime startDate = null;
        if (searchResultDTO.getStartDate()==null && searchResultDTO.getEndDate()==null) {
            endDate = LocalDateTime.now();
            startDate = endDate.minusDays(31);
        }

        String cacheKey = SEARCH_RESULT_COMPREHENSIVE_KEY + keyword + ":" + page + ":" + pageSize;
        Object cacheJson = redisTemplate.opsForValue().get(cacheKey);

        if (cacheJson != null) {
            List<SearchStatusVO> cacheList;
            try {
                cacheList = JSONUtil.parseArray(cacheJson).toList(SearchStatusVO.class);
            } catch (Exception e) {
                redisTemplate.delete(cacheKey);
                cacheList = Collections.emptyList();
            }

            fillDynamicData(cacheList, currentUserId);
            Long total = searchMapper.countResultsByTime(keyword, startDate, endDate);

            return Result.success(new PageResult(total, cacheList)
            );
        }

        //2. 搜索召回
        List<Long> recallIds = searchMapper.getComprehensiveResultIds(keyword, startDate, endDate);

        if (recallIds == null || recallIds.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(Collections.emptyList()), 60, TimeUnit.SECONDS);
            return Result.success(new PageResult(0L, Collections.emptyList()));
        }

        //3. 批量获取帖子详情缓存
        List<String> postKeys = recallIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> postJsonList = redisTemplate.opsForValue().multiGet(postKeys);
        if (postJsonList == null) {
            postJsonList = Collections.emptyList();
        }

        //4. 组装帖子详情
        List<SearchStatusVO> posts = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < recallIds.size(); i++) {
            Long id = recallIds.get(i);
            String json = i < postJsonList.size() ? postJsonList.get(i) : null;

            if (json != null) {
                posts.add(JSONUtil.toBean(json, SearchStatusVO.class));
            } else {
                missIds.add(id);
            }
        }

        //5. 缓存未命中 → 查数据库
        if (!missIds.isEmpty()) {

            List<SearchStatusVO> dbList = searchMapper.getPostByIds(missIds);

            //图片
            List<Long> ids = dbList.stream().map(SearchStatusVO::getId).toList();

            List<StatusImage> images = searchMapper.getImageByIds(ids);

            Map<Long, List<StatusImage>> imageMap = images.stream().collect(Collectors.groupingBy(StatusImage::getStatusId));

            for (SearchStatusVO vo : dbList) {
                vo.setImages(imageMap.get(vo.getId())
                );
            }

            //填充转发原微博
            fillOriginalStatus(dbList, currentUserId);

            //回写Redis
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
            posts.addAll(dbList);
        }

        //6. 动态数据填充    点赞数 / 评论数 / 浏览数
        fillDynamicData(posts, currentUserId);

        //7. 计算综合排序分
        List<ScoredPost> scoredPosts = new ArrayList<>();
        for (SearchStatusVO vo : posts) {

            //7.1 关键词相关性
            double keywordScore = 0;
            String content = vo.getContent();
            if (content != null && content.contains(keyword)) {
                keywordScore += 5;
            }

            //关键词出现次数
            int count = 0;
            int index = 0;
            if (content != null) {
                while ((index = content.indexOf(keyword, index)) != -1) {
                    count++;
                    index += keyword.length();
                }
            }

            keywordScore += Math.log(count + 1) * 3;

            //7.2 时间权重
            long hours = ChronoUnit.HOURS.between(vo.getCreatedAt(), LocalDateTime.now());
            double timeScore = 1.0 / Math.pow(hours + 2, 0.3);

            //7.3 热度分
            Double hotScore = HotScoreCalculator.calculateSearchHotScore(
                    vo.getViewsCount(),
                    vo.getAttitudesCount(),
                    vo.getCommentsCount(),
                    vo.getRepostsCount()
            );

            //7.4 最终综合分
            double finalScore = keywordScore * 0.6 + timeScore * 10 * 0.25 + hotScore * 0.15;

            scoredPosts.add(new ScoredPost(vo, finalScore)
            );
        }

        //8. 全局排序
        scoredPosts.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        //9. 应用层分页
        int start = (page - 1) * pageSize;

        int end = Math.min(start + pageSize, scoredPosts.size());

        List<ScoredPost> pageList = scoredPosts.subList(start, end);

        //10. 提取VO
        List<SearchStatusVO> result = pageList.stream().map(ScoredPost::getPost).toList();

        //11. 用户信息组装
        List<Long> userIds = result.stream().map(SearchStatusVO::getUserId).distinct().toList();

        List<UserVO> users = searchMapper.getUsersInfoByUserIds(userIds);

        Map<Long, UserVO> userMap = users.stream().collect(Collectors.toMap(UserVO::getId, v -> v));

        for (SearchStatusVO vo : result) {
            vo.setUser(userMap.get(vo.getUserId()));
        }

        //填充转发原微博并回写缓存
        fillOriginalStatus(result, currentUserId);
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

            for (SearchStatusVO vo : result) {

                String detailKey = POST_DETAIL_KEY + vo.getId();

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

        //12. 写入搜索缓存
        redisTemplate.opsForValue().set(
                cacheKey,
                JSONUtil.toJsonStr(result),
                SEARCH_RESULT_TTL + new Random().nextInt(300),
                TimeUnit.SECONDS
        );

        //13. 返回结果
        return Result.success(new PageResult(scoredPosts.size(), result));
    }

    @Override
    public Result realtimeResults(SearchResultDTO searchResultDTO) {
        Long userId = BaseContext.getCurrentId();

        //1. 缓存查询
        String keyword = searchResultDTO.getQ();
        Integer page = searchResultDTO.getPage();
        Integer pageSize = searchResultDTO.getPageSize();
        String searchKey = SEARCH_RESULT_REALTIME_KEY + keyword + ":" + page + ":" + pageSize;
        Object searchJson = redisTemplate.opsForValue().get(searchKey);
        if (searchJson != null) {
            List<SearchStatusVO> bean;

            try {
                bean = JSONUtil.parseArray(searchJson).toList(SearchStatusVO.class);
            } catch (Exception e) {
                //防止脏缓存
                redisTemplate.delete(searchKey);
                bean = Collections.emptyList();
            }

            if (bean.isEmpty()) {
                return Result.success(new PageResult(0L, bean));
            }
            fillDynamicData(bean, userId);

            Long total = searchMapper.countResults(keyword);
            return Result.success(new PageResult(total,bean));
        }

        //2. 数据库查询 ids
        searchResultDTO.setPage((page - 1) * pageSize);
        List<Long> statusIds = searchMapper.getResultIds(searchResultDTO);
        if (statusIds.isEmpty()) {
            //无关键字相关的帖子
            redisTemplate.opsForValue().set(
                    searchKey,
                    JSONUtil.toJsonStr(Collections.emptyList()),
                    60, // 空值缓存时间短一点，防止词库更新延迟
                    TimeUnit.SECONDS
            );
            return Result.success(new PageResult(0L, Collections.emptyList()));
        }

        //3. 帖子详情组装
        List<String> idsKey = statusIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(idsKey);
        if (jsonList == null) {
            jsonList = Collections.emptyList();
        }

        List<SearchStatusVO> result = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < statusIds.size(); i++) {

            String json = jsonList.get(i);
            Long id = statusIds.get(i);

            if (json != null) {
                result.add(JSONUtil.toBean(json, SearchStatusVO.class));
            } else {
                missIds.add(id);
            }
        }
        if (!missIds.isEmpty()) {
            List<SearchStatusVO> dbList = searchMapper.getPostByIds(missIds);

            List<Long> missIdList = dbList.stream().map(SearchStatusVO::getId).toList();
            List<StatusImage> images = searchMapper.getImageByIds(missIdList);

            Map<Long, List<StatusImage>> imageMap = images.stream()
                    .collect(Collectors.groupingBy(StatusImage::getStatusId));

            for (SearchStatusVO vo : dbList) {
                vo.setImages(imageMap.get(vo.getId()));
            }

            //填充转发原微博
            fillOriginalStatus(dbList, userId);

            redisTemplate.executePipelined((RedisCallback<Object>) connection ->{

                for (SearchStatusVO searchStatusVO : dbList) {

                    String key = POST_DETAIL_KEY + searchStatusVO.getId();
                    String value = JSONUtil.toJsonStr(searchStatusVO);

                    Long expire = POST_DETAIL_TTL + new Random().nextInt(300);
                    connection.stringCommands().set(
                            key.getBytes(),
                            value.getBytes(),
                            Expiration.seconds(expire),
                            RedisStringCommands.SetOption.UPSERT
                    );
                }
                return null;
            });
            result.addAll(dbList);
        }

        Map<Long, SearchStatusVO> map = result.stream().collect(Collectors.toMap(SearchStatusVO::getId, v -> v));


        //4. 用户信息组装
        List<Long> userIdList = statusIds.stream()
                .map(id -> map.get(id).getUserId())
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
                            key.getBytes(),
                            value.getBytes(),
                            Expiration.seconds(expire),
                            RedisStringCommands.SetOption.UPSERT
                    );
                }
                return null;
            });
        }
        List<SearchStatusVO> finalList = new ArrayList<>();
        for (Long statusId : statusIds) {
            SearchStatusVO vo = map.get(statusId);

            if (vo == null) continue;

            vo.setUser(userMap.get(vo.getUserId()));

            finalList.add(vo);
        }
        //5. 动态数据填充
        fillDynamicData(finalList, userId);

        //填充转发原微博并回写缓存
        fillOriginalStatus(finalList, userId);
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

            for (SearchStatusVO vo : finalList) {

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

        //6. 缓存写入与返回
        redisTemplate.opsForValue().set(
                searchKey,
                JSONUtil.toJsonStr(finalList),
                SEARCH_RESULT_TTL + new Random().nextInt(300),
                TimeUnit.SECONDS
        );
        Long total = searchMapper.countResults(keyword);
        return Result.success(new PageResult(total,finalList));
    }

    private void fillDynamicData(List<SearchStatusVO> list, Long userId) {

        if (list == null || list.isEmpty()) return;

        //1. 批量查询 stats（multiGet）
        for (SearchStatusVO vo : list) {
            String statsKey = POST_STATS_KEY + vo.getId();

            // 点赞数
            Object likeObj = redisTemplate.opsForHash().get(statsKey, "likeCount");

            // 评论数
            Object commentObj = redisTemplate.opsForHash().get(statsKey, "commentCount");

            // 转发数
            Object forwardObj = redisTemplate.opsForHash().get(statsKey, "forwardCount");

            // 浏览数（后面实现）
            Object viewObj = redisTemplate.opsForHash().get(statsKey, "viewCount");


            vo.setAttitudesCount(likeObj == null ? 0 : Integer.parseInt(likeObj.toString()));
            vo.setCommentsCount(commentObj == null ? 0 : Integer.parseInt(commentObj.toString()));
            vo.setRepostsCount(forwardObj == null ? 0 : Integer.parseInt(forwardObj.toString()));
            vo.setViewsCount(viewObj == null ? 0 : Integer.parseInt(viewObj.toString()));

        }

        //2. 是否点赞
        if (userId != null) {

            List<String> likeKeys = list.stream()
                    .map(vo -> LIKE_POST_KEY + vo.getId())
                    .toList();

            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

                for (String key : likeKeys) {
                    connection.setCommands().sIsMember(
                            key.getBytes(),
                            userId.toString().getBytes()
                    );
                }
                return null;
            });

            for (int i = 0; i < list.size(); i++) {
                Boolean isMember = (Boolean) results.get(i);
                list.get(i).setIsLiked(isMember != null && isMember);
            }

        } else {
            list.forEach(vo -> vo.setIsLiked(false));
        }

        //3. 是否关注
        /*if (userId == null || list.isEmpty()) return;

        String followKey = FOLLOW_FOLLOWING_KEY + userId;

        //直接取 set
        Set<Object> myFollowingsRaw = redisTemplate.opsForSet().members(followKey);

        Set<Long> myFollowings = new HashSet<>();

        if (myFollowingsRaw != null) {
            myFollowings = myFollowingsRaw.stream()
                    .map(o -> Long.valueOf(o.toString()))
                    .collect(Collectors.toSet());
        }

        //3.5 填充到 VO 列表
        for (SearchStatusVO vo : list) {
            Long authorId = vo.getUserId();
            // 如果是自己发布的微博，不需要显示关注按钮
            if (userId.equals(authorId)) {
                vo.setIsFollowed(null);
            } else {
                // 检查作者 ID 是否在当前用户的关注列表中
                vo.setIsFollowed(myFollowings.contains(authorId));
            }
        }*/
    }

    /*@Override
    public Result results(SearchResultDTO searchResultDTO) {
        Long userId = BaseContext.getCurrentId();
        String keyword = searchResultDTO.getQ();
        Integer page = searchResultDTO.getPage();
        Integer pageSize = searchResultDTO.getPageSize();
        String searchKey = SEARCH_RESULT_KEY + keyword + ":" + page + ":" + pageSize;
        Object searchJson = redisTemplate.opsForValue().get(searchKey);
        if (searchJson != null) {
            redisTemplate.expire(searchKey,SEARCH_RESULT_TTL+new Random().nextInt(300),TimeUnit.SECONDS);
            List <SearchStatusVO> bean = JSONUtil.parseArray(searchJson).toList(SearchStatusVO.class);

            fillDynamicData(bean, userId);

            Long total = searchMapper.countResults(keyword);
            return Result.success(new PageResult(total,bean));
        }

        List<Long> statusIds = searchMapper.getResultIds(searchResultDTO);
        if (statusIds.isEmpty()) {
            //无关键字相关的帖子
            return Result.success();
        }

        List<String> idsKey = statusIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(idsKey);
        if (jsonList == null) {
            jsonList = Collections.emptyList();
        }

        List<SearchStatusVO> result = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < statusIds.size(); i++) {

            String json = jsonList.get(i);
            Long id = statusIds.get(i);

            if (json != null) {
                result.add(JSONUtil.toBean(json, SearchStatusVO.class));
            } else {
                missIds.add(id);
            }
        }
        if (!missIds.isEmpty()) {
            List<SearchStatusVO> dbList = searchMapper.getPostByIds(missIds);

            List<Long> missIdList = dbList.stream().map(SearchStatusVO::getId).toList();
            List<StatusImage> images = searchMapper.getImageByIds(missIdList);

            Map<Long, List<StatusImage>> imageMap = images.stream()
                    .collect(Collectors.groupingBy(StatusImage::getStatusId));

            for (SearchStatusVO vo : dbList) {
                vo.setImages(imageMap.get(vo.getId()));
            }

            redisTemplate.executePipelined((RedisCallback<Object>) connection ->{

                for (SearchStatusVO searchStatusVO : dbList) {

                    String key = POST_DETAIL_KEY + searchStatusVO.getId();
                    String value = JSONUtil.toJsonStr(searchStatusVO);

                    Long expire = POST_DETAIL_TTL + new Random().nextInt(300);
                    connection.stringCommands().set(
                            key.getBytes(),
                            value.getBytes(),
                            Expiration.seconds(expire),
                            RedisStringCommands.SetOption.UPSERT
                    );
                }
                return null;
            });
            result.addAll(dbList);
        }

        Map<Long, SearchStatusVO> map = result.stream().collect(Collectors.toMap(SearchStatusVO::getId, v -> v));

        Set<Long> userIdSet = new HashSet<>();

        for (Long id : statusIds) {
            SearchStatusVO vo = map.get(id);
            if (vo != null) {
                userIdSet.add(vo.getUserId());
            }
        }

        //转成 List（保证顺序）
        List<Long> userIdList = new ArrayList<>(userIdSet);

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

            List<UserVO> dbUsers = searchMapper.getUsersInfoByUserIds(missUserIds);

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

        Set<Object> myFollowings = redisTemplate.opsForSet().members(FOLLOW_FOLLOWING_KEY + userId);
        Set<Long> myFollowingsIds = myFollowings.stream()
                .map(id -> Long.valueOf(id.toString()))
                .collect(Collectors.toSet());

        List<SearchStatusVO> finalList = new ArrayList<>();
        for (Long statusId : statusIds) {
            SearchStatusVO vo = map.get(statusId);

            vo.setUser(userMap.get(vo.getUserId()));

            finalList.add(vo);

            if (userId == null || !userId.equals(vo.getUserId())) {
                vo.setIsFollowed(myFollowingsIds.contains(vo.getUserId()));
            } else {
                vo.setIsFollowed(null);
            }
        }

        for (SearchStatusVO vo : finalList) {
            String statsKey = POST_STATS_KEY + vo.getId();

            // 点赞数
            Object likeObj = redisTemplate.opsForHash().get(statsKey, "likeCount");

            // 评论数
            Object commentObj = redisTemplate.opsForHash().get(statsKey, "commentCount");

            // 转发数
            Object forwardObj = redisTemplate.opsForHash().get(statsKey, "forwardCount");

            // 浏览数（后面实现）
            Object viewObj = redisTemplate.opsForHash().get(statsKey, "viewCount");


            if (likeObj != null) {
                // 从 Hash 中获取实时点赞数
                vo.setAttitudesCount(Integer.parseInt(likeObj.toString()));
            } else {
                // 如果 Redis 里没计数（比如刚发布的微博）
                if (vo.getAttitudesCount() == null) {
                    vo.setAttitudesCount(0);
                }
            }

            if (commentObj != null) {
                vo.setCommentsCount(Integer.parseInt(commentObj.toString()));
            } else {
                if (vo.getCommentsCount() == null) {
                    vo.setCommentsCount(0);
                }
            }

            if (forwardObj != null) {
                vo.setRepostsCount(Integer.parseInt(forwardObj.toString()));
            } else {
                if (vo.getRepostsCount() == null) {
                    vo.setRepostsCount(0);
                }
            }

            if (viewObj != null) {
                vo.setViewsCount(Integer.parseInt(viewObj.toString()));
            } else {
                vo.setViewsCount(0);
            }

        }

        //7. 当前用户是否点赞
        if (userId != null) {
            //7.1 收集所有微博的 LikeKey，用于批量检查
            List<String> likeKeys = finalList.stream()
                    .map(vo -> LIKE_POST_KEY + vo.getId())
                    .toList();

            //7.2 批量检查当前用户在这些 Key 的 Set 集合中是否存在
            // 注意：RedisTemplate 没有直接的批量 isMember 方法，我们需要用 executePipelined 来优化性能
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

                for (String key : likeKeys) {
                    // 发送 SISMEMBER 命令到管道
                    connection.setCommands().sIsMember(key.getBytes(), userId.toString().getBytes());
                }
                return null; // 返回 null，结果会在 executePipelined 的返回值中获取
            });

            //7.3 将检查结果回填到 VO 中
            for (int i = 0; i < finalList.size(); i++) {
                SearchStatusVO vo = finalList.get(i);
                // Pipeline 返回的是 List<Boolean>
                Boolean isMember = (Boolean) results.get(i);
                vo.setIsLiked(isMember != null && isMember);
            }
        } else {
            // 未登录用户，全部设为 false
            finalList.forEach(vo -> vo.setIsLiked(false));
        }

        redisTemplate.opsForValue().set(
                searchKey,
                JSONUtil.toJsonStr(finalList),
                SEARCH_RESULT_TTL + new Random().nextInt(300),
                TimeUnit.SECONDS
        );
        Long total = searchMapper.countResults(keyword);
        return Result.success(new PageResult(total,finalList));
    }

    private void fillDynamicData(List<SearchStatusVO> list, Long userId) {

        if (list == null || list.isEmpty()) return;

        // ================== 1. 批量查询 stats（multiGet） ==================
        for (SearchStatusVO vo : list) {
            String statsKey = POST_STATS_KEY + vo.getId();

            // 点赞数
            Object likeObj = redisTemplate.opsForHash().get(statsKey, "likeCount");

            // 评论数
            Object commentObj = redisTemplate.opsForHash().get(statsKey, "commentCount");

            // 转发数
            Object forwardObj = redisTemplate.opsForHash().get(statsKey, "forwardCount");

            // 浏览数（后面实现）
            Object viewObj = redisTemplate.opsForHash().get(statsKey, "viewCount");


            if (likeObj != null) {
                // 从 Hash 中获取实时点赞数
                vo.setAttitudesCount(Integer.parseInt(likeObj.toString()));
            } else {
                // 如果 Redis 里没计数（比如刚发布的微博）
                if (vo.getAttitudesCount() == null) {
                    vo.setAttitudesCount(0);
                }
            }

            if (commentObj != null) {
                vo.setCommentsCount(Integer.parseInt(commentObj.toString()));
            } else {
                if (vo.getCommentsCount() == null) {
                    vo.setCommentsCount(0);
                }
            }

            if (forwardObj != null) {
                vo.setRepostsCount(Integer.parseInt(forwardObj.toString()));
            } else {
                if (vo.getRepostsCount() == null) {
                    vo.setRepostsCount(0);
                }
            }

            if (viewObj != null) {
                vo.setViewsCount(Integer.parseInt(viewObj.toString()));
            } else {
                vo.setViewsCount(0);
            }

        }

        // ================== 2. 是否点赞（pipeline版，你这个是安全的） ==================
        if (userId != null) {

            List<String> likeKeys = list.stream()
                    .map(vo -> LIKE_POST_KEY + vo.getId())
                    .toList();

            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

                for (String key : likeKeys) {
                    connection.setCommands().sIsMember(
                            key.getBytes(),
                            userId.toString().getBytes()
                    );
                }
                return null;
            });

            for (int i = 0; i < list.size(); i++) {
                Boolean isMember = (Boolean) results.get(i);
                list.get(i).setIsLiked(isMember != null && isMember);
            }

        } else {
            list.forEach(vo -> vo.setIsLiked(false));
        }

        // ================== 3. 是否关注 ==================
        Set<Long> followIds = Collections.emptySet();

        if (userId != null) {

            Set<Object> raw = redisTemplate.opsForSet()
                    .members(FOLLOW_FOLLOWING_KEY + userId);

            if (raw != null) {
                followIds = raw.stream()
                        .map(o -> Long.valueOf(o.toString()))
                        .collect(Collectors.toSet());
            }
        }

        for (SearchStatusVO vo : list) {

            if (userId == null || userId.equals(vo.getUserId())) {
                vo.setIsFollowed(null);
            } else {
                vo.setIsFollowed(followIds.contains(vo.getUserId()));
            }
        }
    }*/

    @Override
    public Result users(SearchUsersDTO searchUsersDTO) {
        PageHelper.startPage(searchUsersDTO.getPage(),searchUsersDTO.getPageSize());
        Page<SearchUserVO> page = searchMapper.getUsers(searchUsersDTO);
        List<SearchUserVO> records = page.getResult();

        Long currentUserId = BaseContext.getCurrentId();

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

        //4. 批量填充实时统计
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

            SearchStatusVO vo = originalMap.get(id);

            if (vo == null) {
                continue;
            }

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

        //5. 批量判断点赞
        if (currentUserId != null) {

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
