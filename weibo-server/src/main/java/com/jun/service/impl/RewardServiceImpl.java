package com.jun.service.impl;

import com.jun.config.RabbitMQConfig;
import com.jun.context.BaseContext;
import com.jun.dto.CreateRewardActivityDTO;
import com.jun.entity.RewardActivity;
import com.jun.mapper.RewardActivityMapper;
import com.jun.mapper.RewardRecordMapper;
import com.jun.mq.message.RewardJoinMessage;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.service.RewardService;
import com.jun.vo.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.jun.constant.RedisConstants.REWARD_ACTIVITY_INFO_KEY;
import static com.jun.constant.RedisConstants.REWARD_ACTIVITY_STATUS_KEY;

@Service
@Slf4j
public class RewardServiceImpl implements RewardService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String REWARD_ACTIVITY_KEY = "reward:activity:";
    private static final String REWARD_STOCK_SUFFIX = ":stock";
    private static final String REWARD_USERS_SUFFIX = ":users";

    private static final Integer ACTIVITY_TYPE_LIMIT = 1;
    private static final Integer ACTIVITY_TYPE_TIME = 2;

    private static final Integer ACTIVITY_STATUS_PENDING = 0;
    private static final Integer ACTIVITY_STATUS_ACTIVE = 1;
    private static final Integer ACTIVITY_STATUS_ENDED = 2;

    @Resource
    private RewardActivityMapper rewardActivityMapper;
    @Resource
    private RewardRecordMapper rewardRecordMapper;
    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;

    private DefaultRedisScript<Long> rewardJoinScript;

    @PostConstruct
    public void init() {
        rewardJoinScript = new DefaultRedisScript<>();
        rewardJoinScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("reward_join.lua")));
        rewardJoinScript.setResultType(Long.class);
    }

    private Integer parseActivityType(String type) {
        if ("LIMIT_FORWARD".equals(type)) {
            return ACTIVITY_TYPE_LIMIT;
        } else if ("TIME_RANGE".equals(type)) {
            return ACTIVITY_TYPE_TIME;
        }
        throw new IllegalArgumentException("不支持的活动类型: " + type);
    }

    private String formatActivityType(Integer type) {
        if (type == null) return null;
        return type == ACTIVITY_TYPE_LIMIT ? "LIMIT_FORWARD" : "TIME_RANGE";
    }

    @Override
    public Result createActivity(CreateRewardActivityDTO dto) {
        Long userId = BaseContext.getCurrentId();

        // 校验活动类型
        Integer activityType = parseActivityType(dto.getActivityType());

        // 限量模式校验
        if (activityType == ACTIVITY_TYPE_LIMIT) {
            if (dto.getLimitCount() == null || dto.getLimitCount() <= 0) {
                return Result.error("限量模式必须填写有效参与人数上限");
            }
        }

        // 时间范围模式校验
        if (activityType == ACTIVITY_TYPE_TIME) {
            if (dto.getStartTime() == null || dto.getEndTime() == null) {
                return Result.error("时间范围模式必须填写开始时间和结束时间");
            }
        }

        // 解析时间
        LocalDateTime startTime = dto.getStartTime() != null
                ? LocalDateTime.parse(dto.getStartTime(), DTF) : null;
        LocalDateTime endTime = dto.getEndTime() != null
                ? LocalDateTime.parse(dto.getEndTime(), DTF) : null;

        // 时间范围模式：校验 startTime ~ endTime 间隔不得超过半年（182 天）
        if (activityType == ACTIVITY_TYPE_TIME && startTime != null && endTime != null) {
            if (java.time.Duration.between(startTime, endTime).toDays() > 182) {
                return Result.error("时间范围模式开始时间与结束时间间隔不得超过 182 天");
            }
        }

        // 默认值
        if (dto.getFanOnly() == null) {
            dto.setFanOnly(true);
        }

        // 确定活动初始状态
        boolean isPending = startTime != null && startTime.isAfter(LocalDateTime.now());
        int initialStatus = isPending ? ACTIVITY_STATUS_PENDING : ACTIVITY_STATUS_ACTIVE;

        RewardActivity activity = RewardActivity.builder()
                .statusId(dto.getStatusId())
                .creatorId(userId)
                .activityType(activityType)
                .rewardName(dto.getRewardName())
                .rewardDesc(dto.getRewardDesc())
                .fanOnly(dto.getFanOnly())
                .participantLimit(dto.getLimitCount())
                .winnerCount(dto.getWinnerCount())
                .startTime(startTime)
                .endTime(endTime)
                .participantCount(0)
                .drawn(0)
                .status(initialStatus)
                .createTime(LocalDateTime.now())
                .build();

        rewardActivityMapper.insert(activity);

        // 缓存到 Redis：statusId → activityId 映射 + 活动信息
        String statusKey = REWARD_ACTIVITY_STATUS_KEY + activity.getStatusId();
        String infoKey = REWARD_ACTIVITY_INFO_KEY + activity.getId();

        Map<String, String> infoMap = new HashMap<>();
        infoMap.put("activityType", activity.getActivityType().toString());
        infoMap.put("status", String.valueOf(initialStatus));
        infoMap.put("fanOnly", Boolean.TRUE.equals(activity.getFanOnly()) ? "1" : "0");
        infoMap.put("creatorId", activity.getCreatorId().toString());
        if (startTime != null) {
            infoMap.put("startTime", String.valueOf(startTime.toEpochSecond(java.time.ZoneOffset.UTC)));
        }
        if (endTime != null) {
            infoMap.put("endTime", String.valueOf(endTime.toEpochSecond(java.time.ZoneOffset.UTC)));
        }

        // TTL：取最近的时间边界，让缓存自然过期
        long nowEpoch = LocalDateTime.now().toEpochSecond(java.time.ZoneOffset.UTC);
        long ttlSeconds = 86400; // 默认 1 天
        if (isPending && startTime != null) {
            // 未开始：TTL = startTime - now，到期后缓存过期，下次请求回填时激活
            ttlSeconds = startTime.toEpochSecond(java.time.ZoneOffset.UTC) - nowEpoch;
        } else if (endTime != null) {
            // 进行中：TTL = endTime - now，到期后缓存过期，下次请求回填时结束
            ttlSeconds = endTime.toEpochSecond(java.time.ZoneOffset.UTC) - nowEpoch;
        }
        ttlSeconds = Math.max(60, Math.min(ttlSeconds, 86400));

        redisTemplate.opsForValue().set(statusKey, activity.getId().toString(), ttlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForHash().putAll(infoKey, infoMap);
        redisTemplate.expire(infoKey, ttlSeconds, TimeUnit.SECONDS);

        // 限量模式：初始化 Redis 库存
        if (activityType == ACTIVITY_TYPE_LIMIT && dto.getLimitCount() != null) {
            String stockKey = REWARD_ACTIVITY_KEY + activity.getId() + REWARD_STOCK_SUFFIX;
            redisTemplate.opsForValue().set(stockKey, dto.getLimitCount().toString());
        }

        log.info("创建抽奖活动成功: activityId={}, statusId={}, type={}, TTL={}s",
                activity.getId(), dto.getStatusId(), dto.getActivityType(), ttlSeconds);

        return Result.success(Collections.singletonMap("activityId", activity.getId()));
    }

    @Override
    public Result getByStatusId(Long statusId) {
        Long userId = BaseContext.getCurrentId();
        RewardActivity activity = rewardActivityMapper.getByStatusId(statusId);
        if (activity == null) {
            return Result.success(null);
        }
        return Result.success(buildActivityVO(activity, userId));
    }

    @Override
    public Result getById(Long activityId) {
        Long userId = BaseContext.getCurrentId();
        RewardActivity activity = rewardActivityMapper.getById(activityId);
        if (activity == null) {
            return Result.error("活动不存在");
        }
        return Result.success(buildActivityVO(activity, userId));
    }

    @Override
    public Result getParticipants(Long activityId, Integer page, Integer pageSize) {
        if (page == null || page < 1) page = 1;
        if (pageSize == null || pageSize < 1) pageSize = 10;

        int offset = (page - 1) * pageSize;
        Integer total = rewardActivityMapper.countParticipants(activityId);
        if (total == null) total = 0;
        List<ParticipantVO> records = rewardActivityMapper.getParticipantPage(activityId, offset, pageSize);
        if (records == null) records = Collections.emptyList();

        PageResult pageResult = new PageResult();
        pageResult.setTotal(total);
        pageResult.setRecords(records);
        return Result.success(pageResult);
    }

    @Override
    public Result getWinners(Long activityId) {
        List<WinnerVO> winners = rewardActivityMapper.getWinners(activityId);
        if (winners == null) winners = Collections.emptyList();
        return Result.success(winners);
    }

    @Override
    public Result getMyRecords(Integer page, Integer pageSize, Integer onlyWon) {
        Long userId = BaseContext.getCurrentId();
        if (page == null || page < 1) page = 1;
        if (pageSize == null || pageSize < 1) pageSize = 10;

        int offset = (page - 1) * pageSize;
        Integer total;
        List<RewardRecordVO> records;

        if (onlyWon != null && onlyWon == 1) {
            total = rewardActivityMapper.countWonRecordsByUserId(userId);
            records = rewardActivityMapper.getWonRecordsByUserId(userId, offset, pageSize);
        } else {
            total = rewardActivityMapper.countRecordsByUserId(userId);
            records = rewardActivityMapper.getRecordsByUserId(userId, offset, pageSize);
        }

        if (total == null) total = 0;
        if (records == null) records = Collections.emptyList();

        PageResult pageResult = new PageResult();
        pageResult.setTotal(total);
        pageResult.setRecords(records);
        return Result.success(pageResult);
    }

    @Override
    public void handleForwardReward(Long activityId, Long userId, Long statusId) {
        // 1. 从 Redis 缓存读取活动信息（避免高并发下查 DB）
        String infoKey = REWARD_ACTIVITY_INFO_KEY + activityId;
        Map<Object, Object> infoMap = redisTemplate.opsForHash().entries(infoKey);

        Integer activityType;
        Integer activityStatus;
        Boolean fanOnly;
        Long creatorId;
        Long startTimeEpoch = null;
        Long endTimeEpoch = null;

        if (infoMap != null && !infoMap.isEmpty()) {
            // Redis 缓存命中
            activityType = Integer.valueOf(infoMap.get("activityType").toString());
            activityStatus = Integer.valueOf(infoMap.get("status").toString());
            fanOnly = "1".equals(infoMap.get("fanOnly").toString());
            creatorId = Long.valueOf(infoMap.get("creatorId").toString());
            if (infoMap.containsKey("startTime")) {
                startTimeEpoch = Long.valueOf(infoMap.get("startTime").toString());
            }
            if (infoMap.containsKey("endTime")) {
                endTimeEpoch = Long.valueOf(infoMap.get("endTime").toString());
            }
        } else {
            // 缓存未命中 → 查 DB 并回填缓存
            RewardActivity activity = rewardActivityMapper.getById(activityId);
            if (activity == null) {
                log.warn("活动不存在: activityId={}", activityId);
                return;
            }
            activityType = activity.getActivityType();
            activityStatus = activity.getStatus();
            fanOnly = Boolean.TRUE.equals(activity.getFanOnly());
            creatorId = activity.getCreatorId();

            long nowEpoch = LocalDateTime.now().toEpochSecond(java.time.ZoneOffset.UTC);

            // 检查 startTime：未到开始时间则强制标记为未开始
            if (activity.getStartTime() != null
                    && activity.getStartTime().toEpochSecond(java.time.ZoneOffset.UTC) > nowEpoch) {
                activityStatus = ACTIVITY_STATUS_PENDING;
            }

            // 检查 endTime：已过结束时间则强制标记为已结束
            if (activity.getEndTime() != null
                    && activity.getEndTime().toEpochSecond(java.time.ZoneOffset.UTC) <= nowEpoch
                    && ACTIVITY_STATUS_ACTIVE.equals(activityStatus)) {
                activityStatus = ACTIVITY_STATUS_ENDED;
                log.info("缓存过期 + endTime 已过，强制标记为结束: activityId={}", activityId);
            }

            Map<String, String> backup = new HashMap<>();
            backup.put("activityType", activityType.toString());
            backup.put("status", activityStatus.toString());
            backup.put("fanOnly", fanOnly ? "1" : "0");
            backup.put("creatorId", creatorId.toString());
            if (activity.getStartTime() != null) {
                backup.put("startTime", String.valueOf(activity.getStartTime().toEpochSecond(java.time.ZoneOffset.UTC)));
            }
            if (activity.getEndTime() != null) {
                backup.put("endTime", String.valueOf(activity.getEndTime().toEpochSecond(java.time.ZoneOffset.UTC)));
            }
            redisTemplate.opsForHash().putAll(infoKey, backup);
            // 回填用短 TTL，避免长期缓存过期数据
            redisTemplate.expire(infoKey, 600, TimeUnit.SECONDS);
        }

        // 2. 检查活动状态：未开始或已结束均拒绝
        if (!ACTIVITY_STATUS_ACTIVE.equals(activityStatus)) {
            log.warn("活动不可参与: activityId={}, status={}", activityId, activityStatus);
            return;
        }

        // 3. 缓存命中时，额外检查 startTime（防止 TTL 未到 startTime 但缓存仍在）
        if (startTimeEpoch != null
                && startTimeEpoch > LocalDateTime.now().toEpochSecond(java.time.ZoneOffset.UTC)) {
            log.warn("活动尚未开始: activityId={}", activityId);
            return;
        }

        // 3. 检查粉丝限制
        if (Boolean.TRUE.equals(fanOnly)) {
            String followingKey = "follow:following:" + creatorId;
            Boolean isFan = redisTemplate.opsForSet().isMember(followingKey, userId.toString());
            if (!Boolean.TRUE.equals(isFan)) {
                log.warn("非粉丝参与被拒绝: activityId={}, userId={}", activityId, userId);
                return;
            }
        }

        // 4. 执行 Lua 脚本（原子操作：状态检查 + 库存扣减 + 用户记录）
        String stockKey = REWARD_ACTIVITY_KEY + activityId + REWARD_STOCK_SUFFIX;
        String usersKey = REWARD_ACTIVITY_KEY + activityId + REWARD_USERS_SUFFIX;

        Long result = (Long) redisTemplate.execute(
                rewardJoinScript,
                List.of(stockKey, usersKey),
                userId.toString(),
                activityType.toString(),
                activityStatus.toString()
        );

        // 5. 处理结果
        if (result == 0) {
            // 成功获得资格 → 发送MQ异步落库
            RewardJoinMessage message = RewardJoinMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .activityId(activityId)
                    .userId(userId)
                    .statusId(statusId)
                    .retryCount(0)
                    .build();

            CorrelationData correlationData = new CorrelationData(message.getMessageId());
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                    "reward.join",
                    message,
                    correlationData
            );
            log.info("转发参与资格成功: activityId={}, userId={}", activityId, userId);
        } else if (result == 1) {
            log.info("用户已参与: activityId={}, userId={}", activityId, userId);
        } else if (result == 2) {
            log.info("名额已满: activityId={}, userId={}", activityId, userId);
        } else if (result == 3) {
            log.info("活动已结束: activityId={}, userId={}", activityId, userId);
        }
    }

    /**
     * 构建活动 VO，含当前用户参与状态
     */
    private RewardActivityVO buildActivityVO(RewardActivity activity, Long userId) {
        // 当前用户参与状态
        Integer recordStatus = rewardRecordMapper.getStatusByActivityIdAndUserId(activity.getId(), userId);
        String myParticipation;
        if (recordStatus != null) {
            myParticipation = "JOINED";
        } else if (activity.getStatus() == ACTIVITY_STATUS_ENDED) {
            myParticipation = "ENDED";
        } else if (activity.getActivityType() == ACTIVITY_TYPE_LIMIT
                && activity.getParticipantLimit() != null
                && activity.getParticipantCount() != null
                && activity.getParticipantCount() >= activity.getParticipantLimit()) {
            myParticipation = "FULL";
        } else {
            myParticipation = "NONE";
        }

        // 已开奖返回中奖者列表
        boolean drawn = activity.getDrawn() != null && activity.getDrawn() == 1;
        List<WinnerVO> winners = drawn ? rewardActivityMapper.getWinners(activity.getId()) : null;

        return RewardActivityVO.builder()
                .activityId(activity.getId())
                .statusId(activity.getStatusId())
                .creatorId(activity.getCreatorId())
                .activityType(formatActivityType(activity.getActivityType()))
                .rewardName(activity.getRewardName())
                .rewardDesc(activity.getRewardDesc())
                .fanOnly(activity.getFanOnly())
                .participantLimit(activity.getParticipantLimit())
                .winnerCount(activity.getWinnerCount())
                .startTime(activity.getStartTime() != null ? activity.getStartTime().format(DTF) : null)
                .endTime(activity.getEndTime() != null ? activity.getEndTime().format(DTF) : null)
                .status(activity.getStatus())
                .participantCount(activity.getParticipantCount())
                .drawn(drawn)
                .myParticipation(myParticipation)
                .winners(winners)
                .build();
    }

    /**
     * 批量查询帖子扩展信息（statusId → 扩展列表），避免 N+1
     * 先查 Redis 缓存，未命中再批量查 DB 并回填
     */
    @Override
    public Map<Long, List<ExtensionVO>> getExtensionsByStatusIds(List<Long> statusIds) {
        if (statusIds == null || statusIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 1. 批量查 Redis 缓存
        List<String> cacheKeys = statusIds.stream()
                .map(sid -> REWARD_ACTIVITY_STATUS_KEY + sid)
                .toList();
        List<String> cachedIds = redisTemplate.opsForValue().multiGet(cacheKeys);

        // 2. 区分命中/未命中
        Map<Long, Long> activityIdMap = new HashMap<>(); // statusId → activityId
        List<Long> missStatusIds = new ArrayList<>();

        for (int i = 0; i < statusIds.size(); i++) {
            Long sid = statusIds.get(i);
            String cached = (cachedIds != null && i < cachedIds.size()) ? cachedIds.get(i) : null;
            if (cached != null && !cached.isEmpty()) {
                activityIdMap.put(sid, Long.valueOf(cached));
            } else {
                missStatusIds.add(sid);
            }
        }

        // 3. 未命中 → 批量查 DB 并回填缓存
        if (!missStatusIds.isEmpty()) {
            List<RewardActivity> dbActivities = rewardActivityMapper.getByStatusIds(missStatusIds);
            if (dbActivities != null) {
                for (RewardActivity ra : dbActivities) {
                    activityIdMap.put(ra.getStatusId(), ra.getId());
                    // 回填 Redis 缓存，TTL 1 天
                    redisTemplate.opsForValue().set(
                            REWARD_ACTIVITY_STATUS_KEY + ra.getStatusId(),
                            ra.getId().toString(),
                            1, TimeUnit.DAYS);
                }
            }
        }

        // 4. 组装结果
        if (activityIdMap.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, List<ExtensionVO>> result = new HashMap<>();
        for (Map.Entry<Long, Long> entry : activityIdMap.entrySet()) {
            result.put(entry.getKey(), List.of(
                    ExtensionVO.builder().type("REWARD").id(entry.getValue()).build()
            ));
        }
        return result;
    }
}