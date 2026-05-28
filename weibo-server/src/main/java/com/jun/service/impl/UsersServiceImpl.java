package com.jun.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.dto.ScrollDTO;
import com.jun.dto.StatusPageQueryDTO;
import com.jun.dto.UserProfileDTO;
import com.jun.entity.StatusImage;
import com.jun.entity.User;
import com.jun.exception.UploadAvatarException;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.result.ScrollResult;
import com.jun.utils.OssUtil;
import com.jun.vo.StatusVO;
import com.jun.vo.UserCountVO;
import com.jun.vo.UsersInfoVO;
import com.jun.mapper.UsersMapper;
import com.jun.service.UsersService;
import com.jun.context.BaseContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.jun.constant.MessageConstant.*;
import static com.jun.constant.RedisConstants.*;

@Slf4j
@Service
public class UsersServiceImpl implements UsersService {

    @Resource
    private UsersMapper usersMapper;
    @Resource
    private OssUtil ossUtil;
    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public Result<UsersInfoVO> me() {
        Long userId = BaseContext.getCurrentId();

        //1. 查缓存（不含 stats，stats 从 Redis 实时取）
        String cacheKey = USER_PROFILE_KEY + userId;
        Object cacheJson = redisTemplate.opsForValue().get(cacheKey);
        if (cacheJson != null) {
            try {
                UsersInfoVO cached = JSONUtil.toBean(cacheJson.toString(), UsersInfoVO.class);
                fillUserStats(cached, userId);
                return Result.success(cached);
            } catch (Exception e) {
                log.warn("用户信息缓存反序列化失败 key={}", cacheKey, e);
                redisTemplate.delete(cacheKey);
            }
        }

        //2. 查数据库（只查用户基本信息，不含 stats）
        UsersInfoVO usersInfoVO = usersMapper.getUserInfoWithoutStats(userId);
        if (usersInfoVO == null) {
            return Result.error("用户不存在");
        }

        //3. 写缓存（只缓存基本信息，不含 stats）
        redisTemplate.opsForValue().set(
                cacheKey,
                JSONUtil.toJsonStr(usersInfoVO),
                USER_PROFILE_TTL + new Random().nextInt(600),
                TimeUnit.SECONDS
        );

        //4. 从 Redis 实时获取 stats
        fillUserStats(usersInfoVO, userId);

        return Result.success(usersInfoVO);
    }

    @Override
    public Result<String> updateProfile(UserProfileDTO userProfileDTO,MultipartFile avatarFile) {
        Long userId = BaseContext.getCurrentId();
        String fileUrl = null;
        if (avatarFile != null && !avatarFile.isEmpty()) {

            if (userProfileDTO.getAvatar()!=null) {
                try {
                    String avatarPath = usersMapper.getAvatarByUserId(userId);
                    List<String> split = StrUtil.split(avatarPath, "/");
                    List<String> collect = split.stream().skip(3).toList();
                    avatarPath = StrUtil.join("/", collect);
                    ossUtil.delete(avatarPath);
                } catch (Exception e) {
                    log.error("文件删除失败");
                    throw new UploadAvatarException(UPLOAD_IMAGE_ERROR);
                }
            }

            try {
                String folder = "weibo/avatars/";
                String originalFilename = avatarFile.getOriginalFilename();
                String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
                String objectName = folder + UUID.randomUUID() + extension;

                fileUrl = ossUtil.upload(avatarFile.getInputStream(), objectName);
            } catch (Exception e) {
                log.error("文件上传失败");
                throw new UploadAvatarException(UPLOAD_IMAGE_ERROR);
            }
        }

        if (userProfileDTO == null) {
            return Result.error("对象为空");
        }
        userProfileDTO.setAvatar(fileUrl);
        int isSuccess = usersMapper.updateProfile(userProfileDTO,userId);
        if (isSuccess <= 0){
            return Result.error("更新个人信息失败！");
        }
        return Result.success(fileUrl);
    }

    public Result<PageResult> getPost(StatusPageQueryDTO statusPageQueryDTO) {
        Long userId = BaseContext.getCurrentId();

        int pageNum = statusPageQueryDTO.getPage();
        int pageSize = statusPageQueryDTO.getPageSize();

        String zsetKey = USER_POST_IDS_KEY + userId;

        int start = (pageNum - 1) * pageSize;
        int end = start + pageSize - 1;

        //1. 从ZSet取对应用户的微博ID（USER_POST_IDS_KEY）
        Set<String> idSet = redisTemplate.opsForZSet().reverseRange(zsetKey, start, end);

        if (idSet == null || idSet.isEmpty()) {
            //1.1 为空则加载数据库和构建缓存
            return loadFromDbAndBuildCache(statusPageQueryDTO, userId, zsetKey);
        }
        List<Long> ids = idSet.stream()
                .map(Long::valueOf)
                .toList();

        //2. 批量查详情缓存
        List<String> keys = ids.stream()
                .map(id -> POST_DETAIL_KEY + id)
                .toList();
        //2.1 jsonList 会根据 idSet 中 desc 查询到的排列存放数据
        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);

        List<StatusVO> result = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < ids.size(); i++) {
            String json = jsonList.get(i);
            Long id = ids.get(i);

            if (json != null && !json.isEmpty()) {
                result.add(JSONUtil.toBean(json, StatusVO.class));
            } else {
                missIds.add(id);
            }
        }

        //3. miss → 查数据库
        if (!missIds.isEmpty()) {

            List<StatusVO> dbList = usersMapper.getPostByMissIds(missIds);

            List<Long> missIdList = dbList.stream().map(StatusVO::getId).toList();
            List<StatusImage> images = usersMapper.getImageByIds(missIdList);

            Map<Long, List<StatusImage>> imageMap = images.stream()
                    .collect(Collectors.groupingBy(StatusImage::getStatusId));

            for (StatusVO vo : dbList) {
                vo.setImages(imageMap.get(vo.getId()));

                // 点赞（简单写法，可后续优化pipeline）
                if (userId == null) {
                    vo.setIsLiked(false);
                } else {
                    Boolean isLiked = redisTemplate.opsForSet().isMember(LIKE_POST_KEY + vo.getId(), userId.toString());
                    vo.setIsLiked(isLiked);
                }
            }

            //填充转发原微博
            fillOriginalStatus(dbList);

            //4. pipeline 回写缓存
            //executePipelined 的设计初衷是“只发不收”
            //RedisCallback 是为了获得对底层连接的直接控制权，从而在管道（Pipeline）模式下，
            //通过 connection.stringCommands().set(...) 这种原生方式，以最快的速度批量写入数据
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

                for (StatusVO vo : dbList) {
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
                //要求回调函数返回 null
                return null;
            });
            result.addAll(dbList);
        }

        //5. 按ZSet顺序重排
        Map<Long, StatusVO> map = result.stream()
                .collect(Collectors.toMap(StatusVO::getId, v -> v));

        List<StatusVO> finalList = new ArrayList<>();

        for (Long id : ids) {
            finalList.add(map.get(id));
        }

        //填充转发原微博
        fillOriginalStatus(finalList);
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

            for (StatusVO vo : finalList) {

                String cacheKey = POST_DETAIL_KEY + vo.getId();

                String cacheValue = JSONUtil.toJsonStr(vo);

                long expireTime =
                        POST_DETAIL_TTL + new Random().nextInt(300);

                connection.stringCommands().set(
                        cacheKey.getBytes(),
                        cacheValue.getBytes(),
                        Expiration.seconds(expireTime),
                        RedisStringCommands.SetOption.UPSERT
                );
            }

            return null;
        });

        //6. 获取即时点赞数
        for (StatusVO vo : finalList) {
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

            //更新原微博实时统计
            StatusVO originalStatus = vo.getOriginalStatus();

            if (originalStatus != null) {

                String originalStatsKey =
                        POST_STATS_KEY + originalStatus.getId();

                Map<Object, Object> originalStats =
                        redisTemplate.opsForHash().entries(originalStatsKey);

                if (originalStats != null && !originalStats.isEmpty()) {

                    Object originalLikeObj =
                            originalStats.get("likeCount");

                    Object originalCommentObj =
                            originalStats.get("commentCount");

                    Object originalForwardObj =
                            originalStats.get("forwardCount");

                    Object originalViewObj =
                            originalStats.get("viewCount");

                    if (originalLikeObj != null) {
                        originalStatus.setAttitudesCount(
                                Integer.parseInt(
                                        originalLikeObj.toString()
                                )
                        );
                    }

                    if (originalCommentObj != null) {
                        originalStatus.setCommentsCount(
                                Integer.parseInt(
                                        originalCommentObj.toString()
                                )
                        );
                    }

                    if (originalForwardObj != null) {
                        originalStatus.setRepostsCount(
                                Integer.parseInt(
                                        originalForwardObj.toString()
                                )
                        );
                    }

                    if (originalViewObj != null) {
                        originalStatus.setViewsCount(
                                Integer.parseInt(
                                        originalViewObj.toString()
                                )
                        );
                    }
                }
            }
        }

        //7. 当前用户是否点赞（含原微博）
        if (userId != null) {
            //7.1 收集所有微博的 LikeKey（含原微博），用于批量检查
            List<String> likeKeys = new ArrayList<>();
            for (StatusVO vo : finalList) {
                likeKeys.add(LIKE_POST_KEY + vo.getId());
                if (vo.getOriginalStatus() != null) {
                    likeKeys.add(LIKE_POST_KEY + vo.getOriginalStatus().getId());
                }
            }

            //7.2 批量检查当前用户在这些 Key 的 Set 集合中是否存在
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : likeKeys) {
                    connection.setCommands().sIsMember(key.getBytes(), userId.toString().getBytes());
                }
                return null;
            });

            //7.3 将检查结果回填到 VO 中
            int idx = 0;
            for (StatusVO vo : finalList) {
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

        //8. total
        //zCard 获取一个有序集合（Sorted Set / ZSet）中元素的总个数
        Long total = redisTemplate.opsForZSet().zCard(zsetKey);
        return Result.success(new PageResult(total, finalList));
    }

    private void fillOriginalStatus(List<StatusVO> finalList) {

        // 1. 收集 originalStatusId
        List<Long> originalIds = finalList.stream()
                .filter(vo -> vo.getOriginalStatusId() != null)
                //originalStatus 不存在才查
                .filter(vo -> vo.getOriginalStatus() == null)
                .map(StatusVO::getOriginalStatusId)
                .distinct()
                .toList();

        if (originalIds.isEmpty()) {
            return;
        }

        // 2. 查询原微博
        List<StatusVO> originalList =
                usersMapper.getPostByIds(originalIds);

        // 3. 查询原微博图片
        List<StatusImage> originalImages =
                usersMapper.getImageByIds(originalIds);

        Map<Long, List<StatusImage>> originalImageMap =
                originalImages.stream()
                        .collect(Collectors.groupingBy(
                                StatusImage::getStatusId
                        ));

        // 4. 填充原微博信息
        for (StatusVO vo : originalList) {

            vo.setImages(originalImageMap.get(vo.getId()));
            if (BaseContext.getCurrentId() == null) {

                vo.setIsLiked(false);

            } else {

                Boolean isLiked = redisTemplate.opsForSet().isMember(
                        LIKE_POST_KEY + vo.getId(),
                        BaseContext.getCurrentId().toString()
                );

                vo.setIsLiked(isLiked != null && isLiked);
            }
        }

        // 5. 构建 map
        Map<Long, StatusVO> originalMap =
                originalList.stream()
                        .collect(Collectors.toMap(
                                StatusVO::getId,
                                v -> v
                        ));

        // 6. 回填 originalStatus（只填充 originalStatus 为 null 的，避免覆盖已有的）
        for (StatusVO vo : finalList) {

            if (vo.getOriginalStatusId() != null && vo.getOriginalStatus() == null) {

                vo.setOriginalStatus(
                        originalMap.get(
                                vo.getOriginalStatusId()
                        )
                );
            }
        }
    }

    private Result<PageResult> loadFromDbAndBuildCache(StatusPageQueryDTO statusPageQueryDTO, Long userId, String zsetKey) {
        statusPageQueryDTO.setUserId(userId);
        PageHelper.startPage(statusPageQueryDTO.getPage(), statusPageQueryDTO.getPageSize());
        Page<StatusVO> page = usersMapper.getPostByUserId(statusPageQueryDTO);

        if (page.isEmpty()) {
            log.info("帖子列表为空");
            return Result.success();
        }

        List<Long> ids = new ArrayList<>();
        for (StatusVO statusVO : page) {
            ids.add(statusVO.getId());
        }

        List<StatusImage> images = usersMapper.getImageByIds(ids);
        Map<Long, List<StatusImage>> groupByStatusId = images.stream()
                .collect(Collectors.groupingBy(StatusId -> StatusId.getStatusId()));

        for (StatusVO statusVO : page) {
            List<StatusImage> image = groupByStatusId.get(statusVO.getId());
            statusVO.setImages(image);

            if (userId ==null) {
                statusVO.setIsLiked(false);
            }
            else {
                Boolean isLiked = redisTemplate.opsForSet().isMember(LIKE_POST_KEY + statusVO.getId(), userId.toString());
                statusVO.setIsLiked(isLiked);
            }
        }

        long total = page.getTotal();
        List<StatusVO> record = page.getResult();

        fillOriginalStatus(record);

        //写ZSet + 写详情缓存
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

            for (StatusVO vo : record) {

                vo.setImages(groupByStatusId.get(vo.getId()));

                // ZSet（时间线）
                connection.zAdd(
                        zsetKey.getBytes(),
                        vo.getCreatedAt().toEpochSecond(ZoneOffset.UTC),
                        vo.getId().toString().getBytes()
                );

                // 详情缓存
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

        return Result.success(new PageResult(total, record));
    }

    @Override
    public Result<UserProfileDTO> getProfile() {
        Long userId = BaseContext.getCurrentId();
        UserProfileDTO userProfileDTO = usersMapper.getProfile(userId);
        return Result.success(userProfileDTO);
    }

    // ======================== 获取指定用户信息 ========================

    @Override
    public Result<UsersInfoVO> getUserInfo(Long userId) {
        if (userId == null) {
            return Result.error("用户 ID 不能为空");
        }

        String cacheKey = USER_PROFILE_KEY + userId;

        //1. 查缓存（不含 stats，stats 从 Redis 实时取）
        Object cacheJson = redisTemplate.opsForValue().get(cacheKey);
        if (cacheJson != null) {
            try {
                UsersInfoVO cached = JSONUtil.toBean(cacheJson.toString(), UsersInfoVO.class);
                fillUserStats(cached, userId);
                return Result.success(cached);
            } catch (Exception e) {
                log.warn("用户信息缓存反序列化失败 key={}", cacheKey, e);
                redisTemplate.delete(cacheKey);
            }
        }

        //2. 查数据库（只查用户基本信息，不含 stats）
        UsersInfoVO usersInfoVO = usersMapper.getUserInfoWithoutStats(userId);
        if (usersInfoVO == null) {
            return Result.error("用户不存在");
        }

        //3. 写缓存（只缓存基本信息，不含 stats）
        redisTemplate.opsForValue().set(
                cacheKey,
                JSONUtil.toJsonStr(usersInfoVO),
                USER_PROFILE_TTL + new Random().nextInt(600),
                TimeUnit.SECONDS
        );

        //4. 从 Redis 实时获取 stats
        fillUserStats(usersInfoVO, userId);

        return Result.success(usersInfoVO);
    }

    /**
     * 从 Redis 实时获取关注数、粉丝数、获赞数，回填到 VO
     */
    private void fillUserStats(UsersInfoVO vo, Long userId) {
        Object followingObj = redisTemplate.opsForValue().get(COUNT_FOLLOWING_KEY + userId);
        Object fansObj = redisTemplate.opsForValue().get(COUNT_FANS_KEY + userId);
        Object likesObj = redisTemplate.opsForValue().get(COUNT_USER_LIKES_KEY + userId);

        int followingCount = followingObj == null ? 0 : Integer.parseInt(followingObj.toString());
        int followersCount = fansObj == null ? 0 : Integer.parseInt(fansObj.toString());
        int likesCount = likesObj == null ? 0 : Integer.parseInt(likesObj.toString());

        // Redis 没有时 fallback DB，并回写 Redis
        if (followingObj == null || fansObj == null || likesObj == null) {
            UserCountVO dbStats = usersMapper.countByUserId(userId);
            if (dbStats != null) {
                if (followingObj == null) {
                    followingCount = dbStats.getFollowingCount() != null ? dbStats.getFollowingCount() : 0;
                    redisTemplate.opsForValue().set(COUNT_FOLLOWING_KEY + userId, String.valueOf(followingCount), USER_PROFILE_TTL, TimeUnit.SECONDS);
                }
                if (fansObj == null) {
                    followersCount = dbStats.getFollowersCount() != null ? dbStats.getFollowersCount() : 0;
                    redisTemplate.opsForValue().set(COUNT_FANS_KEY + userId, String.valueOf(followersCount), USER_PROFILE_TTL, TimeUnit.SECONDS);
                }
                if (likesObj == null) {
                    likesCount = dbStats.getLikesCount() != null ? dbStats.getLikesCount() : 0;
                    redisTemplate.opsForValue().set(COUNT_USER_LIKES_KEY + userId, String.valueOf(likesCount), USER_PROFILE_TTL, TimeUnit.SECONDS);
                }
            }
        }

        UserCountVO stats = new UserCountVO();
        stats.setFollowingCount(followingCount);
        stats.setFollowersCount(followersCount);
        stats.setLikesCount(likesCount);
        vo.setStats(stats);
    }

    // ======================== 获取指定用户帖子列表（游标分页） ========================

    @Override
    public Result<ScrollResult> getUserPosts(Long userId, ScrollDTO scrollDTO) {
        if (userId == null) {
            return Result.error("用户 ID 不能为空");
        }

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

        String zsetKey = USER_POST_IDS_KEY + userId;

        //2. 从 ZSET 获取帖子 ID（游标分页）
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(
                                zsetKey,
                                Double.NEGATIVE_INFINITY,
                                lastScore,
                                offset,
                                pageSize
                        );
        if (tuples == null || tuples.isEmpty()) {
            return Result.success(new ScrollResult(null, 0, Collections.emptyList()));
        }

        List<Long> statusIds = tuples.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .toList();

        //3. 批量查缓存
        List<String> keys = statusIds.stream().map(id -> POST_DETAIL_KEY + id).toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);

        List<StatusVO> result = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < statusIds.size(); i++) {
            String json = jsonList.get(i);
            Long id = statusIds.get(i);

            if (json != null && !json.isEmpty()) {
                result.add(JSONUtil.toBean(json, StatusVO.class));
            } else {
                missIds.add(id);
            }
        }

        //4. miss → 查数据库
        Long currentUserId = BaseContext.getCurrentId();
        if (!missIds.isEmpty()) {
            List<StatusVO> dbList = usersMapper.getPostByMissIds(missIds);

            List<Long> missIdList = dbList.stream().map(StatusVO::getId).toList();
            List<StatusImage> images = usersMapper.getImageByIds(missIdList);

            Map<Long, List<StatusImage>> imageMap = images.stream()
                    .collect(Collectors.groupingBy(StatusImage::getStatusId));

            for (StatusVO vo : dbList) {
                vo.setImages(imageMap.get(vo.getId()));
                if (currentUserId == null) {
                    vo.setIsLiked(false);
                } else {
                    Boolean isLiked = redisTemplate.opsForSet().isMember(LIKE_POST_KEY + vo.getId(), currentUserId.toString());
                    vo.setIsLiked(isLiked);
                }
            }

            fillOriginalStatus(dbList);

            // 回写缓存
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (StatusVO vo : dbList) {
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

        //5. 按 ZSET 顺序重排
        Map<Long, StatusVO> map = result.stream()
                .collect(Collectors.toMap(StatusVO::getId, v -> v));

        List<StatusVO> finalList = new ArrayList<>();
        for (Long id : statusIds) {
            StatusVO vo = map.get(id);
            if (vo != null) {
                finalList.add(vo);
            }
        }

        //6. 填充转发原微博并回写缓存
        fillOriginalStatus(finalList);
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (StatusVO vo : finalList) {
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

        //7. 获取即时统计数据（含原微博）
        for (StatusVO vo : finalList) {
            fillPostStats(vo);
            StatusVO originalStatus = vo.getOriginalStatus();
            if (originalStatus != null) {
                fillPostStats(originalStatus);
            }
        }

        //8. 当前用户是否点赞（含原微博）
        if (currentUserId != null) {
            List<String> likeKeys = new ArrayList<>();
            for (StatusVO vo : finalList) {
                likeKeys.add(LIKE_POST_KEY + vo.getId());
                if (vo.getOriginalStatus() != null) {
                    likeKeys.add(LIKE_POST_KEY + vo.getOriginalStatus().getId());
                }
            }

            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : likeKeys) {
                    connection.setCommands().sIsMember(key.getBytes(), currentUserId.toString().getBytes());
                }
                return null;
            });

            int idx = 0;
            for (StatusVO vo : finalList) {
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

        //9. 计算下一页游标
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

        return Result.success(new ScrollResult(minScore, newOffset, finalList));
    }

    private void fillPostStats(StatusVO vo) {
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
