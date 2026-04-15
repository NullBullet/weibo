package com.jun.weiboserver;

import cn.hutool.core.util.StrUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Collectors;

@SpringBootTest
class WeiboServerApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void test(){
        String a = "https://java-ai-kurisutina.oss-cn-beijing.aliyuncs.com/weibo/avatars/a49e2061-f2dc-42da-ad67-a12ea778049d.jpeg";
        List<String> split = StrUtil.split(a, "/");
        for (String s : split) {
            System.out.println(s);
        }
        List<String> collect = split.stream().skip(3).toList();
        String join = StrUtil.join("/", collect);
        System.out.println(join);
    }
}
