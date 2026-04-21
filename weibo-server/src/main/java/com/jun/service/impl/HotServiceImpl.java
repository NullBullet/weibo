package com.jun.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.context.BaseContext;
import com.jun.dto.PageQueryDTO;
import com.jun.entity.StatusImage;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.vo.HotStatusVO;
import com.jun.vo.HotTopicVO;
import com.jun.vo.UserVO;
import com.jun.mapper.HotMapper;
import com.jun.service.HotService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.jun.constant.RedisConstants.FOLLOW_FOLLOWING_KEY;

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
    public Result<PageResult> getPosts(PageQueryDTO pageQueryDTO, Long currentUserId) {
        PageHelper.startPage(pageQueryDTO.getPage(),pageQueryDTO.getPageSize());
        Page<HotStatusVO> page = hotMapper.getPosts();
        if(page==null||page.isEmpty()){
            return Result.success();
        }

        List<HotStatusVO> records = page.getResult();
        List<Long> statusIds = new ArrayList<>();
        Set<Long> setIds = new LinkedHashSet<>();
        for (HotStatusVO record : records) {
            statusIds.add(record.getId());
            setIds.add(record.getUserId());
        }

        List<UserVO> users = hotMapper.getUsersInfoByUserIds(setIds);
        Map<Long, List<UserVO>> groupByUserId = users.stream()
                .collect(Collectors.groupingBy(userIds -> userIds.getId()));

        List<StatusImage> images = hotMapper.getImageByIds(statusIds);
        Map<Long, List<StatusImage>> groupByStatusId = images.stream()
                .collect(Collectors.groupingBy(StatusId -> StatusId.getStatusId()));

        Set<Object> myFollowings = redisTemplate.opsForSet().members(FOLLOW_FOLLOWING_KEY + currentUserId);
        Set<Long> myFollowingsIds = myFollowings.stream()
                .map(id -> Long.valueOf(id.toString()))
                .collect(Collectors.toSet());

        for (HotStatusVO record : records) {
            List<UserVO> user = groupByUserId.get(record.getUserId());
            List<StatusImage> image = groupByStatusId.get(record.getId());
            if (currentUserId == null || !currentUserId.equals(record.getUserId())) {
                record.setIsFollowed(myFollowingsIds.contains(record.getUserId()));
            } else {
                record.setIsFollowed(null);
            }

            record.setUser(user);
            record.setImages(image);
        }

        long total = page.getTotal();

        return Result.success(new PageResult(total,records));
    }
}
