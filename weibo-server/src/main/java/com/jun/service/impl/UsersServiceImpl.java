package com.jun.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.dto.StatusPageQueryDTO;
import com.jun.dto.UserProfileDTO;
import com.jun.entity.StatusImage;
import com.jun.entity.User;
import com.jun.exception.UploadAvatarException;
import com.jun.result.PageResult;
import com.jun.result.Result;
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
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.ZoneOffset;
import java.util.*;
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
        User user = usersMapper.getUserInfoByUserId(userId);
        UsersInfoVO usersInfoVO = new UsersInfoVO();

        UserCountVO userCountVO = usersMapper.countByUserId(userId);

        BeanUtils.copyProperties(user, usersInfoVO);

        usersInfoVO.setStats(userCountVO);
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

        //todo 转发评论数未实现
        //6. 获取即时点赞数
        List<String> likeCountKeys = finalList.stream()
                .map(vo -> LIKE_COUNT_POST_KEY + vo.getId())
                .toList();
        List<String> likeCounts = redisTemplate.opsForValue().multiGet(likeCountKeys);

        for (int i = 0; i < finalList.size(); i++) {
            StatusVO vo = finalList.get(i);
            String realTimeCount = likeCounts.get(i);

            if (realTimeCount != null && !realTimeCount.isEmpty()) {
                // 用 Redis 里的实时计数覆盖 VO 里的旧计数
                vo.setAttitudesCount(Integer.valueOf(realTimeCount));
            } else {
                // 如果 Redis 里没计数（比如刚发布的微博）
                if (vo.getAttitudesCount() == null) {
                    vo.setAttitudesCount(0);
                }
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
                StatusVO vo = finalList.get(i);
                // Pipeline 返回的是 List<Boolean>
                Boolean isMember = (Boolean) results.get(i);
                vo.setIsLiked(isMember != null && isMember);
            }
        } else {
            // 未登录用户，全部设为 false
            finalList.forEach(vo -> vo.setIsLiked(false));
        }

        //8. total
        //zCard 获取一个有序集合（Sorted Set / ZSet）中元素的总个数
        Long total = redisTemplate.opsForZSet().zCard(zsetKey);
        return Result.success(new PageResult(total, finalList));
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

}
