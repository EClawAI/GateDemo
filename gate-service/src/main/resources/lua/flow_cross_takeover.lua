-- B2: 跨实例 owner 迁移原子脚本。
--
-- KEYS[1] = flowKey       (gate:flow:<flowId>)
-- KEYS[2] = byPlayerKey   (gate:flow:byplayer:<playerId>)
-- ARGV[1] = newOwnerGateId
-- ARGV[2] = newExpiresAtMs
-- ARGV[3] = ttlMs (PEXPIRE 用)
-- ARGV[4] = nowMs (与 expiresAt 比较)
--
-- 返回:
--   "EXPIRED"                    -- hash 不存在或已过期
--   "SAME"                       -- 改写前后 ownerGateId 相同（幂等）
--   "<previousOwnerGateId>"      -- 否则返回旧 owner 字符串
local raw = redis.call('HGETALL', KEYS[1])
if (#raw == 0) then return "EXPIRED" end

local tbl = {}
for i = 1, #raw, 2 do tbl[raw[i]] = raw[i + 1] end

local expiresAt = tonumber(tbl['expiresAt'] or '0')
local nowMs = tonumber(ARGV[4])
if (expiresAt <= nowMs) then return "EXPIRED" end

local prev = tbl['ownerGateId'] or ''
redis.call('HSET', KEYS[1], 'ownerGateId', ARGV[1], 'expiresAt', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
redis.call('PEXPIRE', KEYS[2], ARGV[3])

if (prev == ARGV[1]) then return "SAME" end
return prev
