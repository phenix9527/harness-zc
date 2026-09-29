-- ============================================================================
-- 优惠券领取：Redis 侧原子预扣
--
-- 用途：挡在高并发写入最前面，用一次 Redis 往返同时完成
--       1) 用户维度幂等标记（SETNX，一人一单）
--       2) 库存判断 + 扣减（DECR）
-- 只要有一段在 Java 里分开写，就必然出现中间态（扣了库存但标记没打上）导致超发。
--
-- KEYS[1] = 库存 key，例如 coupon:stock:{activityId}
--           ★ 要求调用方提前初始化为活动库存总量；key 不存在时本脚本按 0 处理
--             （宁可不发，也不能超发）
-- KEYS[2] = 用户标记 key，例如 coupon:user:{activityId}:{userId}
--           ★ 必须带 userId：这是「一人一单」的判据，只到活动维度就没有意义了
--             （Java 侧见 CouponClaimService:141 的拼装）
-- ARGV[1] = 用户标记的过期秒数（一般 >= 活动时长 + 余量）
--
-- 返回值：
--   >= 0  预扣成功，值为扣减后的剩余库存
--   -1    用户已领过（幂等命中，调用方应返回「上次结果」而不是报错）
--   -2    库存不足（已抢光）
--
-- 注意点：
--   1. 库存 key 必须由活动预热任务写入，不要在业务路径上 SETNX 补写，
--      否则「没人初始化」会被当成没库存，反而安全；但初始化逻辑漏了会静默少发。
--   2. 用户在 DB 里已有券、而 Redis 没标记时（例如 Redis 刚扩容/被清），
--      本脚本会放过去，最终由 DB 唯一索引 uk_user_activity 拦下 —— 这是设计好的第二道防线。
--   3. 脚本里不写日志、不做重试：Redis 里跑 Lua 会阻塞单线程，越短越好。
-- ============================================================================

local userExists = redis.call('SETNX', KEYS[2], '1')
if userExists == 0 then
    return -1
end

local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock <= 0 then
    -- 库存不足要把刚打上的用户标记回滚，否则用户明明没领到却被永久挡住
    redis.call('DEL', KEYS[2])
    return -2
end

redis.call('DECR', KEYS[1])
redis.call('EXPIRE', KEYS[2], ARGV[1])
return stock - 1
