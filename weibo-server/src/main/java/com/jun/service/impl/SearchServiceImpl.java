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
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.SearchService;
import com.jun.vo.*;
import jakarta.annotation.Resource;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

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
        return null;
    }

    @Override
    public Result realtimeResults(SearchResultDTO searchResultDTO) {
        Long userId = BaseContext.getCurrentId();

        //1. 缓存查询
        String keyword = searchResultDTO.getQ();
        Integer page = searchResultDTO.getPage();
        Integer pageSize = searchResultDTO.getPageSize();
        String searchKey = SEARCH_RESULT_KEY + keyword + ":" + page + ":" + pageSize;
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
}
