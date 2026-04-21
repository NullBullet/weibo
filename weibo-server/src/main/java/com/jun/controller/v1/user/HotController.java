package com.jun.controller.v1.user;

import com.jun.constant.JwtClaimsConstant;
import com.jun.context.BaseContext;
import com.jun.dto.PageQueryDTO;
import com.jun.properties.JwtProperties;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.utils.JwtUtil;
import com.jun.vo.HotTopicVO;
import com.jun.service.HotService;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.jun.constant.JwtClaimsConstant.USER_ID;

@Slf4j
@RestController
@RequestMapping("v1/hot")
public class HotController {

    @Resource
    private HotService hotService;
    @Resource
    private JwtProperties jwtProperties;

    @GetMapping("/topics")
    public Result<List<HotTopicVO>> topics(@RequestParam(value = "size", required = false) Integer size){
        log.info("获取热搜话题榜{}",size);
        return hotService.topics(size);
    }

    //todo 该接口需要使用游标滚动分页，对七天内的帖子筛选后（粉丝量/点赞数）,未做redis缓存
    // 计算热度分(互动总分 = 点赞数 + 评论数*3 + 转发数*5)
    // log_10(互动总分+1)/(小时数+2)^1.8
    @GetMapping("/posts")
    public Result<PageResult> posts(PageQueryDTO pageQueryDTO, HttpServletRequest request){
        log.info("获取热门微博{}",pageQueryDTO);

        Long currentUserId = BaseContext.getCurrentId();
        if(currentUserId==null){
                // 1. 从 Header 中获取 Authorization
                // 前端传的是 Authorization
                String token = request.getHeader("Authorization");

                // 2. 判断 Token 是否为空
                if (token != null && !token.isEmpty()) {

                    // 3. 直接解析 Token (因为你的 Token 没有 Bearer 前缀，所以不需要 substring)
                    // 注意：如果 token 是非法的或者过期的，parseJWT 会抛出异常，会被下方的 catch 捕获
                    Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);

                    // 4. 从 Claims 中提取 userId
                    // 注意：claims.get("userId") 返回的是 Object，需要转成 String 再转 Long
                    Object userIdObj = claims.get(USER_ID);
                    if (userIdObj != null) {
                        currentUserId = Long.valueOf(userIdObj.toString());
                        log.info("用户已登录，解析到 ID: {}", currentUserId);
                    }
                } else {
                    log.info("请求头中未包含 Token，视为游客访问");
                }
            }
        return hotService.getPosts(pageQueryDTO,currentUserId);
        }
    }
