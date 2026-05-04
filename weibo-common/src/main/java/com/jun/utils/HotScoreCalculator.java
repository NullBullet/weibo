package com.jun.utils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 热度分数计算工具类
 * 负责：纯算法逻辑，无状态
 */
public class HotScoreCalculator {

    /**
     * 计算热度分
     * 公式：(log10(浏览+1) + log10(互动+1)) / (小时数 + 2)^1.8
     *
     * @param viewCount    浏览量
     * @param likeCount    点赞数
     * @param commentCount 评论数
     * @param forwardCount 转发数
     * @param createTime   发布时间戳 (秒)
     * @return 热度分数
     */
    public static double calculate(long viewCount, long likeCount, long commentCount, long forwardCount, long createTime) {
        // 1. 计算互动总分 = 点赞 + 评论*3 + 转发*5
        long interactionScore = likeCount + (commentCount * 3) + (forwardCount * 5);

        // 2. 计算时间差（小时）
        long nowSeconds = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        // 确保时间差不为负数（防止服务器时间不同步导致的异常）
        double hours = Math.max(0, (nowSeconds - createTime) / 3600.0);

        // 3. 分子：对数降权
        // Math.log10(x + 1) 防止 log(0) 报错，同时压缩数值量级
        double viewPart = Math.log10(viewCount + 1);
        double interactionPart = Math.log10(interactionScore + 1);
        
        double numerator = viewPart + interactionPart;

        // 4. 分母：时间衰减
        // (小时数 + 2) ^ 1.8，指数越大，老内容掉榜越快
        double denominator = Math.pow(hours + 2, 1.8);

        return numerator / denominator;
    }
}