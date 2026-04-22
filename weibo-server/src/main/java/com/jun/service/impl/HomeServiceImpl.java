package com.jun.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.dto.HomeQueryDTO;
import com.jun.entity.StatusImage;
import com.jun.exception.IsEmpty;
import com.jun.mapper.HomeMapper;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.HomeService;
import com.jun.vo.HomeStatusVO;
import  com.jun.context.BaseContext;
import com.jun.vo.UserVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.jun.constant.MessageConstant.FOLLOWING_IS_EMPTY;

@Service
public class HomeServiceImpl implements HomeService {

    @Resource
    private HomeMapper homeMapper;

    @Override
    public Result getPosts(HomeQueryDTO homeQueryDTO) {
        Long userId = BaseContext.getCurrentId();
        List<Long> followingIds = homeMapper.getFollowing(userId);
        if (followingIds==null||followingIds.isEmpty()) {
            throw new IsEmpty(FOLLOWING_IS_EMPTY);
        }

        PageHelper.startPage(homeQueryDTO.getPage(),homeQueryDTO.getPageSize());
        Page<HomeStatusVO> page = homeMapper.getPosts(homeQueryDTO,followingIds);
        if(page==null||page.isEmpty()){
            return Result.success();
        }

        Set<Long> setIds = new LinkedHashSet<>();
        List<Long> statusIds = new ArrayList<>();
        List<HomeStatusVO> records = page.getResult();
        for (HomeStatusVO record : records) {
            statusIds.add(record.getId());
            setIds.add(record.getUserId());
        }

        List<UserVO> users = homeMapper.getUsersInfoByUserIds(setIds);
        Map<Long, List<UserVO>> groupByUserId = users.stream()
                .collect(Collectors.groupingBy(userIds -> userIds.getId()));

        List<StatusImage> images = homeMapper.getImageByIds(statusIds);
        Map<Long, List<StatusImage>> groupByStatusId = images.stream()
                .collect(Collectors.groupingBy(StatusId -> StatusId.getStatusId()));

        for (HomeStatusVO record : records) {
            List<UserVO> user = groupByUserId.get(record.getUserId());
            List<StatusImage> image = groupByStatusId.get(record.getId());
            record.setUser(user);
            record.setImages(image);
        }

        long total = page.getTotal();
        return Result.success(new PageResult(total,records));
    }
}
