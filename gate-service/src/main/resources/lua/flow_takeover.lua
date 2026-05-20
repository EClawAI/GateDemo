--[[
flow_takeover.lua

原子完成「单玩家单 flow」语义下的新 flow 注册：
  1. 读取 byplayer 索引 KEYS[2] = `gate:flow:byplayer:<playerId>` 的旧 flowId（若存在）；
  2. 若旧 flowId 存在且 ≠ 新 flowId（防同 ID 自我替换误删），DEL 旧 flow hash；
  3. SET byplayer 索引为新 flowId（PX <ttlMs> 与新 flow 同寿）；
  4. HMSET 新 flow hash 的字段（playerId, gameId, ownerGateId, createdAt, expiresAt, lastSeqAnchor）；
  5. PEXPIRE 新 flow hash 为 ttlMs。

参数：
  KEYS[1] = gate:flow:<newFlowId>
  KEYS[2] = gate:flow:byplayer:<playerId>
  ARGV[1] = newFlowId
  ARGV[2] = playerId
  ARGV[3] = gameId
  ARGV[4] = ownerGateId
  ARGV[5] = createdAt   (epoch ms, string)
  ARGV[6] = expiresAt   (epoch ms, string)
  ARGV[7] = lastSeqAnchor (string, 默认 "0")
  ARGV[8] = ttlMs       (整数字符串)

返回：被踢掉的旧 flowId（字符串），若无则返回 nil。
]]

local newFlowKey   = KEYS[1]
local byPlayerKey  = KEYS[2]

local newFlowId    = ARGV[1]
local playerId     = ARGV[2]
local gameId       = ARGV[3]
local ownerGateId  = ARGV[4]
local createdAt    = ARGV[5]
local expiresAt    = ARGV[6]
local lastSeqAnchor = ARGV[7]
local ttlMs        = tonumber(ARGV[8])

local oldFlowId = redis.call('GET', byPlayerKey)

if oldFlowId and oldFlowId ~= newFlowId then
    -- 旧 flow hash key 由调用方拼接 prefix；为简洁，假定 newFlowKey 形如 prefix .. newFlowId，
    -- 用 string.sub 推断 prefix（截掉末尾 newFlowId 长度）。
    local prefix = string.sub(newFlowKey, 1, #newFlowKey - #newFlowId)
    redis.call('DEL', prefix .. oldFlowId)
end

redis.call('SET', byPlayerKey, newFlowId, 'PX', ttlMs)

redis.call('HSET', newFlowKey,
    'playerId',      playerId,
    'gameId',        gameId,
    'ownerGateId',   ownerGateId,
    'createdAt',     createdAt,
    'expiresAt',     expiresAt,
    'lastSeqAnchor', lastSeqAnchor,
    'detachedAt',    '0')
redis.call('PEXPIRE', newFlowKey, ttlMs)

if oldFlowId and oldFlowId ~= newFlowId then
    return oldFlowId
end
return false
