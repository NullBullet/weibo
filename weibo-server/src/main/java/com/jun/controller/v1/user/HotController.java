package com.jun.controller.v1.user;

import com.jun.dto.PageQueryDTO;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.vo.HotTopicVO;
import com.jun.service.HotService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("v1/hot")
public class HotController {

    @Resource
    private HotService hotService;

    @GetMapping("/topics")
    public Result<List<HotTopicVO>> topics(@RequestParam(value = "size", required = false) Integer size){
        log.info("获取热搜话题榜{}",size);
        return hotService.topics(size);
    }

    //todo 该接口需要使用游标滚动分页，对七天内的帖子筛选后（粉丝量/点赞数）,
    // 计算热度分(互动总分 = 点赞数 + 评论数*3 + 转发数*5)
    // log_10(互动总分+1)/(小时数+2)^1.8
    @GetMapping("/posts")
    public Result<PageResult> posts(PageQueryDTO pageQueryDTO){
        log.info("获取热门微博{}",pageQueryDTO);
        return hotService.getPosts(pageQueryDTO);
    }
}
