package com.jun.service.impl;

import com.jun.config.RabbitMQConfig;
import com.jun.constant.MessageConstant;
import com.jun.dto.RegisterDTO;
import com.jun.dto.UserLoginDTO;
import com.jun.entity.User;
import com.jun.exception.AccountLockedException;
import com.jun.exception.AccountNotFoundException;
import com.jun.exception.PasswordErrorException;
import com.jun.mq.message.LoginLogMessage;
import com.jun.properties.JwtProperties;
import com.jun.result.Result;
import com.jun.utils.CaptchaUtil;
import com.jun.utils.JwtUtil;
import com.jun.vo.CaptchaVO;
import com.jun.vo.UserLoginVO;
import com.jun.mapper.AuthMapper;
import com.jun.mapper.UsersMapper;
import com.jun.service.AuthService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.jun.constant.JwtClaimsConstant.USER_ID;
import static com.jun.constant.RedisConstants.CAPTCHA_KEY;
import static com.jun.constant.RedisConstants.CAPTCHA_TTL;
import static com.jun.constant.RedisConstants.IP_CAPTCHA_KEY;
import static com.jun.constant.RedisConstants.IP_LOGIN_KEY;
import static com.jun.constant.RedisConstants.LOGIN_FAIL_KEY;
import static com.jun.constant.RedisConstants.USER_STATS_KEY;
import static com.jun.constant.RedisConstants.USER_STATS_TTL;
import static com.jun.constant.StatusConstant.ENABLE;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final int IP_CAPTCHA_LIMIT = 5;
    private static final int IP_CAPTCHA_WINDOW = 120;

    // 登录 IP 防刷：每 IP 每分钟最多 5 次
    private static final int IP_LOGIN_LIMIT = 5;
    private static final int IP_LOGIN_WINDOW = 60;

    // 账号锁定：连续失败 5 次后锁定 5 分钟
    private static final int ACCOUNT_FAIL_LIMIT = 5;
    private static final int ACCOUNT_LOCK_WINDOW = 300;

    @Resource
    private AuthMapper authMapper;
    @Resource
    private UsersMapper usersMapper;
    @Resource
    private JwtProperties jwtProperties;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public Result<UserLoginVO> login(UserLoginDTO userLoginDTO) {
        String username = userLoginDTO.getUsername();
        String password = userLoginDTO.getPassword();
        String ip = userLoginDTO.getIpAddress();
        String userAgent = userLoginDTO.getUserAgent();

        //1. 校验验证码（在一切限流/查库之前，不消耗限流次数）
        String captchaId = userLoginDTO.getCaptchaId();
        String captchaCode = userLoginDTO.getCaptchaCode();
        if (captchaId == null || captchaCode == null) {
            return Result.error("验证码不能为空");
        }
        String captchaRedisKey = CAPTCHA_KEY + captchaId;
        String storedCode = (String) redisTemplate.opsForValue().get(captchaRedisKey);
        if (storedCode == null) {
            return Result.error("验证码已过期，请重新获取");
        }
        if (!storedCode.equalsIgnoreCase(captchaCode.trim())) {
            return Result.error("验证码错误");
        }
        // 验证码一次性使用，防重放
        redisTemplate.delete(captchaRedisKey);

        //2. IP 级别限流（查数据库之前拦截）
        String ipKey = IP_LOGIN_KEY + ip;
        Object ipCountObj = redisTemplate.opsForValue().get(ipKey);
        int ipCount = ipCountObj != null ? Integer.parseInt(ipCountObj.toString()) : 0;
        if (ipCount >= IP_LOGIN_LIMIT) {
            Long remaining = redisTemplate.getExpire(ipKey, TimeUnit.SECONDS);
            long retryAfter = remaining != null && remaining > 0 ? remaining : IP_LOGIN_WINDOW;
            log.warn("IP登录频率超限: ip={}, count={}", ip, ipCount);
            return Result.error("操作太频繁，请" + retryAfter + "秒后再试");
        }

        //3. 账号级别锁定检查（查数据库之前）
        String failKey = LOGIN_FAIL_KEY + username;
        Object failCountObj = redisTemplate.opsForValue().get(failKey);
        int failCount = failCountObj != null ? Integer.parseInt(failCountObj.toString()) : 0;
        if (failCount >= ACCOUNT_FAIL_LIMIT) {
            Long remaining = redisTemplate.getExpire(failKey, TimeUnit.SECONDS);
            long retryAfter = remaining != null && remaining > 0 ? remaining : ACCOUNT_LOCK_WINDOW;
            log.warn("账号已被临时锁定: username={}, failCount={}", username, failCount);
            return Result.error("账号已被临时锁定，请" + retryAfter + "秒后再试");
        }

        //4. 查找用户
        User user = authMapper.getByUsername(username);
        if (user == null) {
            incrementFailCounters(ipKey, ipCount, failKey, failCount);
            sendLoginLog(null, username, ip, userAgent, 0, "账号不存在");
            // 统一错误消息，防止用户名枚举
            throw new AccountNotFoundException("用户名或密码错误");
        }

        //5. 验证密码
        password = DigestUtils.md5DigestAsHex(password.getBytes());
        if (!password.equals(user.getPassword())) {
            incrementFailCounters(ipKey, ipCount, failKey, failCount);
            sendLoginLog(user.getId(), username, ip, userAgent, 0, "密码错误");
            // 统一错误消息，防止用户名枚举
            throw new PasswordErrorException("用户名或密码错误");
        }

        //6. 检查账户状态（管理员永久锁定）
        if (!Objects.equals(user.getStatus(), ENABLE)) {
            sendLoginLog(user.getId(), username, ip, userAgent, 0, "账号被锁定");
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        //7. 登录成功，清除失败计数
        redisTemplate.delete(failKey);

        String token = buildToken(user.getId());
        sendLoginLog(user.getId(), username, ip, userAgent, 1, null);

        UserLoginVO userLoginVO = UserLoginVO.builder()
                .userId(user.getId().toString())
                .token(token).build();
        return Result.success(userLoginVO);
    }

    /**
     * 递增 IP 和账号维度的失败计数器
     */
    private void incrementFailCounters(String ipKey, int ipCount, String failKey, int failCount) {
        // IP 计数
        if (ipCount == 0) {
            redisTemplate.opsForValue().set(ipKey, "1", IP_LOGIN_WINDOW, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().increment(ipKey);
        }
        // 账号失败计数
        if (failCount == 0) {
            redisTemplate.opsForValue().set(failKey, "1", ACCOUNT_LOCK_WINDOW, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().increment(failKey);
        }
    }

    @Override
    public Result captcha(String ip) {
        // IP 防刷
        String ipKey = IP_CAPTCHA_KEY + ip;
        Object countObj = redisTemplate.opsForValue().get(ipKey);
        int count = countObj != null ? Integer.parseInt(countObj.toString()) : 0;
        if (count >= IP_CAPTCHA_LIMIT) {
            return Result.error("操作太频繁，请稍后再试");
        }

        // 生成验证码
        CaptchaUtil.CaptchaResult captcha = CaptchaUtil.generate();
        String captchaId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Redis 存验证码
        redisTemplate.opsForValue().set(CAPTCHA_KEY + captchaId, captcha.code(), CAPTCHA_TTL, TimeUnit.SECONDS);

        // IP 计数
        if (count == 0) {
            redisTemplate.opsForValue().set(ipKey, "1", IP_CAPTCHA_WINDOW, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().increment(ipKey);
        }

        CaptchaVO vo = new CaptchaVO();
        vo.setCaptchaId(captchaId);
        vo.setImage(captcha.image());
        return Result.success(vo);
    }

    @Override
    public Result register(RegisterDTO registerDTO, String ipAddress, String userAgent) {
        String username = registerDTO.getUsername();
        String password = registerDTO.getPassword();
        String captchaId = registerDTO.getCaptchaId();
        String captchaCode = registerDTO.getCaptchaCode();

        // 参数校验
        if (username == null || username.trim().isEmpty()) {
            return Result.error("用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            return Result.error("密码至少6位");
        }
        if (captchaId == null || captchaCode == null) {
            return Result.error("验证码不能为空");
        }

        // 校验验证码
        String redisKey = CAPTCHA_KEY + captchaId;
        String storedCode = (String) redisTemplate.opsForValue().get(redisKey);
        if (storedCode == null) {
            return Result.error("验证码已过期");
        }
        if (!storedCode.equalsIgnoreCase(captchaCode.trim())) {
            return Result.error("验证码错误");
        }
        // 验证通过后删除
        redisTemplate.delete(redisKey);

        // 检查用户名唯一性
        User existing = authMapper.getByUsername(username);
        if (existing != null) {
            log.warn("注册失败，用户名已存在: {}", username);
            sendLoginLog(null, username, ipAddress, userAgent, 0, "用户名已存在");
            return Result.error("用户名已存在");
        }

        // 创建用户
        User user = new User();
        user.setUsername(username);
        user.setPassword(DigestUtils.md5DigestAsHex(password.getBytes()));
        user.setNickname(username); // 默认昵称为用户名
        user.setAvatar("https://java-ai-kurisutina.oss-cn-beijing.aliyuncs.com/weibo/avatars/default.jpg"); // 默认头像

        int rows = authMapper.saveUser(user);
        if (rows <= 0 || user.getId() == null) {
            return Result.error("注册失败");
        }

        log.info("用户注册成功: id={}, username={}", user.getId(), username);

        // 初始化 user_statistics DB
        usersMapper.initUserStatistics(user.getId());

        // 初始化 user:stats Redis
        String statsKey = USER_STATS_KEY + user.getId();
        redisTemplate.opsForHash().putAll(statsKey, Map.of(
                "followingCount", 0,
                "followersCount", 0,
                "statusesCount", 0,
                "likesCount", 0
        ));
        redisTemplate.expire(statsKey, USER_STATS_TTL, TimeUnit.SECONDS);

        // 注册成功后直接登录，生成 token
        String token = buildToken(user.getId());

        // 写入登录日志
        sendLoginLog(user.getId(), username, ipAddress, userAgent, 1, null);

        UserLoginVO userLoginVO = UserLoginVO.builder()
                .userId(user.getId().toString())
                .token(token).build();
        return Result.success(userLoginVO);
    }

    private String buildToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(USER_ID, userId);
        return JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims);
    }

    private void sendLoginLog(Long userId, String username, String ipAddress,
                              String userAgent, Integer loginStatus, String failReason) {
        long now = System.currentTimeMillis();

        LoginLogMessage message = LoginLogMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .userId(userId)
                .username(username)
                .loginType(1)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .loginStatus(loginStatus)
                .failReason(failReason)
                .activeTimestamp(now)
                .retryCount(0)
                .build();

        CorrelationData correlationData = new CorrelationData(message.getMessageId());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                RabbitMQConfig.LOGIN_LOG,
                message,
                correlationData
        );
    }
}
