package com.jun.controller.v1.user;

import com.jun.dto.HomeQueryDTO;
import com.jun.result.Result;
import com.jun.service.HomeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequestMapping("/v1/home")
@RestController
public class HomeController {

    @Resource
    private HomeService homeService;

    @GetMapping("/timeline")
    public Result timeline(HomeQueryDTO homeQueryDTO){
        log.info("获取首页微博列表：{}",homeQueryDTO);
        return homeService.getPosts(homeQueryDTO);
    }
}
