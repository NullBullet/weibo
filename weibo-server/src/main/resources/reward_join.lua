-- 抽奖活动参与资格原子扣减脚本
-- KEYS[1] = reward:activity:{activityId}:stock  (剩余参与人数，仅限量模式)
-- KEYS[2] = reward:activity:{activityId}:users   (参与用户SET)
-- ARGV[1] = userId (字符串)
-- ARGV[2] = activityType (1-限量, 2-时间范围)
-- ARGV[3] = activityStatus (1-进行中, 其他-已结束)

-- 返回值：
-- 0: 成功获得资格
-- 1: 已参与
-- 2: 名额已满（仅限量模式）
-- 3: 活动已结束

local stockKey = KEYS[1]
local usersKey = KEYS[2]
local userId = ARGV[1]
local activityType = tonumber(ARGV[2])
local activityStatus = tonumber(ARGV[3])

-- 1. 检查活动状态（原子性关键：防止 Java 层状态检查与 Lua 执行之间的竞争）
if activityStatus ~= 1 then
    return 3  -- 活动已结束
end

-- 2. 检查用户是否已参与
local isMember = redis.call('SISMEMBER', usersKey, userId)
if isMember == 1 then
    return 1  -- 已参与
end

-- 3. 限量模式：检查并扣减库存
if activityType == 1 then
    local stock = redis.call('GET', stockKey)
    if not stock or tonumber(stock) <= 0 then
        return 2  -- 名额已满
    end
    redis.call('DECR', stockKey)
end

-- 4. 保存参与用户
redis.call('SADD', usersKey, userId)

return 0  -- 成功