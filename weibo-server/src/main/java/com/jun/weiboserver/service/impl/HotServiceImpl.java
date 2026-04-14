package com.jun.weiboserver.service.impl;

import com.jun.result.Result;
import com.jun.vo.HotTopicVO;
import com.jun.weiboserver.mapper.HotMapper;
import com.jun.weiboserver.service.HotService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HotServiceImpl implements HotService {

    @Resource
    private HotMapper hotMapper;

    @Override
    public Result<List<HotTopicVO>> topics(Integer size) {
        List<HotTopicVO> listTopics = hotMapper.getTopics(size);
        return Result.success(listTopics);
    }
}
