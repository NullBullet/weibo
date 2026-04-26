package com.jun.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.context.BaseContext;
import com.jun.dto.SearchResultDTO;
import com.jun.entity.StatusImage;
import com.jun.mapper.SearchMapper;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.SearchService;
import com.jun.vo.HotStatusVO;
import com.jun.vo.StatusVO;
import com.jun.vo.UserVO;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.jun.constant.RedisConstants.FOLLOW_FOLLOWING_KEY;
import static com.jun.constant.RedisConstants.LIKE_POST_KEY;

@Service
public class SearchServiceImpl implements SearchService {

    @Resource
    private SearchMapper searchMapper;
    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public Result results(SearchResultDTO searchResultDTO) {
        Long currentUserId = BaseContext.getCurrentId();
        PageHelper.startPage(searchResultDTO.getPage(),searchResultDTO.getPageSize());
        Page<HotStatusVO> page =  searchMapper.getResults(searchResultDTO);

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

        List<UserVO> users = searchMapper.getUsersInfoByUserIds(setIds);
        Map<Long, List<UserVO>> groupByUserId = users.stream()
                .collect(Collectors.groupingBy(userIds -> userIds.getId()));

        List<StatusImage> images = searchMapper.getImageByIds(statusIds);
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

            if (currentUserId==null) {
                record.setIsLiked(false);
            }
            else {
                Boolean isLiked = redisTemplate.opsForSet().isMember(LIKE_POST_KEY + record.getId(), currentUserId.toString());
                record.setIsLiked(isLiked);
            }

            record.setUser(user);
            record.setImages(image);
        }

        long total = page.getTotal();

        return Result.success(new PageResult(total,records));
    }
}
