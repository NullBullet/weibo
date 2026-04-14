package com.jun.weiboserver.controller.v1.user;

import com.jun.result.Result;
import com.jun.vo.HotTopicVO;
import com.jun.weiboserver.service.HotService;
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

}
