package com.jun.controller.v1.user;

import com.jun.dto.CreateRewardActivityDTO;
import com.jun.result.Result;
import com.jun.service.RewardService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequestMapping("/v1/reward")
@RestController
public class RewardController {

    @Resource
    private RewardService rewardService;

    /**
     * 创建抽奖活动
     */
    @PostMapping("/activity")
    public Result createActivity(@RequestBody CreateRewardActivityDTO dto) {
        log.info("创建抽奖活动: {}", dto);
        return rewardService.createActivity(dto);
    }

    /**
     * 按微博 ID 查询活动
     */
    @GetMapping("/activity")
    public Result getActivityByStatusId(@RequestParam Long statusId) {
        log.info("按微博ID查询活动: statusId={}", statusId);
        return rewardService.getByStatusId(statusId);
    }

    /**
     * 按活动 ID 查询详情
     */
    @GetMapping("/activity/{activityId}")
    public Result getActivityById(@PathVariable Long activityId) {
        log.info("查询活动详情: activityId={}", activityId);
        return rewardService.getById(activityId);
    }

    /**
     * 获取参与名单
     */
    @GetMapping("/activity/{activityId}/participants")
    public Result getParticipants(@PathVariable Long activityId,
                                  @RequestParam(required = false, defaultValue = "1") Integer page,
                                  @RequestParam(required = false, defaultValue = "10") Integer pageSize) {
        log.info("获取参与名单: activityId={}, page={}, pageSize={}", activityId, page, pageSize);
        return rewardService.getParticipants(activityId, page, pageSize);
    }

    /**
     * 获取中奖名单
     */
    @GetMapping("/activity/{activityId}/winners")
    public Result getWinners(@PathVariable Long activityId) {
        log.info("获取中奖名单: activityId={}", activityId);
        return rewardService.getWinners(activityId);
    }

    /**
     * 获取我的抽奖记录
     */
    @GetMapping("/records/me")
    public Result getMyRecords(@RequestParam(required = false, defaultValue = "1") Integer page,
                               @RequestParam(required = false, defaultValue = "10") Integer pageSize,
                               @RequestParam(required = false, defaultValue = "0") Integer onlyWon) {
        log.info("获取我的抽奖记录: page={}, pageSize={}, onlyWon={}", page, pageSize, onlyWon);
        return rewardService.getMyRecords(page, pageSize, onlyWon);
    }
}