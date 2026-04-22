package com.jun.controller.v1.user;

import com.jun.dto.StatusesImageDTO;
import com.jun.result.Result;
import com.jun.service.StatusesService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequestMapping("/v1/statuses")
@RestController
public class StatusesController {

    @Resource
    private StatusesService statusesService;

    @PostMapping
    public Result statuses(@RequestBody StatusesImageDTO statusesImageDTO){
        log.info("发布微博：{}",statusesImageDTO);
        return statusesService.saveStatus(statusesImageDTO);
    }

}
