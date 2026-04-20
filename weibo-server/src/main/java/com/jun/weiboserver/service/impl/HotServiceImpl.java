package com.jun.weiboserver.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.dto.PageQueryDTO;
import com.jun.entity.StatusImage;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.vo.HotStatusVO;
import com.jun.vo.HotTopicVO;
import com.jun.vo.UserVO;
import com.jun.weiboserver.mapper.HotMapper;
import com.jun.weiboserver.service.HotService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HotServiceImpl implements HotService {

    @Resource
    private HotMapper hotMapper;

    @Override
    public Result<List<HotTopicVO>> topics(Integer size) {
        List<HotTopicVO> listTopics = hotMapper.getTopics(size);
        return Result.success(listTopics);
    }

    @Override
    public Result<PageResult> getPosts(PageQueryDTO pageQueryDTO) {
        PageHelper.startPage(pageQueryDTO.getPage(),pageQueryDTO.getPageSize());
        Page<HotStatusVO> page = hotMapper.getPosts();
        if(page==null||page.isEmpty()){
            return Result.success();
        }

        List<HotStatusVO> records = page.getResult();
        List<Long> ids = new ArrayList<>();
        List<Long> statusIds = new ArrayList<>();
        for (HotStatusVO record : records) {
            ids.add(record.getUserId());
            statusIds.add(record.getId());
        }

        List<UserVO> users = hotMapper.getUsersInfoByUserIds(ids);
        Map<Long, List<UserVO>> groupByUserId = users.stream()
                .collect(Collectors.groupingBy(userIds -> userIds.getId()));

        List<StatusImage> images = hotMapper.getImageByIds(statusIds);
        Map<Long, List<StatusImage>> groupByStatusId = images.stream()
                .collect(Collectors.groupingBy(StatusId -> StatusId.getStatusId()));

        for (HotStatusVO record : records) {
            List<UserVO> user = groupByUserId.get(record.getUserId());
            List<StatusImage> image = groupByStatusId.get(record.getId());
            record.setUser(user);
            record.setImages(image);
        }

        long total = page.getTotal();

        return Result.success(new PageResult(total,records));
    }
}
