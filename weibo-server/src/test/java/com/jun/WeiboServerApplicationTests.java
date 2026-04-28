package com.jun;

import com.jun.entity.Statuses;
import com.jun.mapper.TestMapper;
import jakarta.annotation.Resource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.jun.constant.RedisConstants.USER_POST_IDS_KEY;

@SpringBootTest
class WeiboServerApplicationTests {

    /*@Test
    void contextLoads() {
    }*/

    /*@Test
    void test(){
        String a = "https://java-ai-kurisutina.oss-cn-beijing.aliyuncs.com/weibo/avatars/a49e2061-f2dc-42da-ad67-a12ea778049d.jpeg";
        List<String> split = StrUtil.split(a, "/");
        for (String s : split) {
            System.out.println(s);
        }
        List<String> collect = split.stream().skip(3).toList();
        String join = StrUtil.join("/", collect);
        System.out.println(join);
    }*/

    /*@Resource
    private RedisTemplate redisTemplate;*/

    /*@Test
    void text1(){
        redisTemplate.opsForSet().add("test_key", "test_value");
    }*/

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private TestMapper testMapper;

    //@Test
    void createUserPostIds() {

        // 1. 查所有用户ID
        Set<Long> userIds = testMapper.getUserId();

        for (Long userId : userIds) {

            // 2. 查该用户的所有帖子
            List<Statuses> statuses = testMapper.getStatusByUserId(userId);

            String key = USER_POST_IDS_KEY + userId;

            // 3. 批量写入 ZSet（推荐用 pipeline）
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {

                for (Statuses status : statuses) {

                    Long postId = status.getId();

                    // 👉 用发布时间做 score（核心！）
                    long score = status.getCreatedAt().toEpochSecond(ZoneOffset.UTC);

                    connection.zAdd(
                            key.getBytes(),
                            score,
                            postId.toString().getBytes()
                    );
                }

                return null;
            });
            System.out.println("用户 " + userId + " 的帖子ZSet构建完成");
        }
    }
}
