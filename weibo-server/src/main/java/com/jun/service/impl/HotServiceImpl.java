package com.jun.service.impl;

import cn.hutool.json.JSONUtil;
import com.jun.dto.ScrollDTO;
import com.jun.entity.StatusImage;
import com.jun.result.Result;
import com.jun.result.ScrollResult;
import com.jun.vo.HotStatusVO;
import com.jun.vo.HotTopicVO;
import com.jun.vo.StatusVO;
import com.jun.mapper.HotMapper;
import com.jun.service.HotService;
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
                result.add(JSONUtil.toBean(json,HotStatusVO.class));
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

            //填充转发原微博
            fillOriginalStatus(dbList, currentUserId);

            //6. pipeline 回写缓存
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

        //9. 获取关注列表
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

        //填充转发原微博并回写缓存
        fillOriginalStatus(finalList, currentUserId);
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

        //11. 获取即时点赞数
        for (HotStatusVO vo : finalList) {
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

        //12. 当前用户是否点赞
        if (currentUserId != null) {
            //12.1 收集所有微博的 LikeKey，用于批量检查
            List<String> likeKeys = finalList.stream()
                    .map(vo -> LIKE_POST_KEY + vo.getId())
                    .toList();

            //12.2 批量检查当前用户在这些 Key 的 Set 集合中是否存在
            // 注意：RedisTemplate 没有直接的批量 isMember 方法，我们需要用 executePipelined 来优化性能
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

                for (String key : likeKeys) {
                    // 发送 SISMEMBER 命令到管道
                    connection.setCommands().sIsMember(key.getBytes(), currentUserId.toString().getBytes());
                }
                return null; // 返回 null，结果会在 executePipelined 的返回值中获取
            });

            //12.3 将检查结果回填到 VO 中
            for (int i = 0; i < finalList.size(); i++) {
                HotStatusVO vo = finalList.get(i);
                // Pipeline 返回的是 List<Boolean>
                Boolean isMember = (Boolean) results.get(i);
                vo.setIsLiked(isMember != null && isMember);
            }
        } else {
            // 未登录用户，全部设为 false
            finalList.forEach(vo -> vo.setIsLiked(false));
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

                //3.3 回写缓存
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

            HotStatusVO vo = originalMap.get(id);

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

        //6. 回填 originalStatus（只填充 originalStatus 为 null 的，避免覆盖已有的）
        for (HotStatusVO vo : finalList) {

            if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() == null) {

                vo.setOriginalStatus(originalMap.get(vo.getOriginalStatusId()));
            }
        }
    }
}
