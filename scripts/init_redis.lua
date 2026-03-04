-- Redis 初始化脚本
-- 用于创建必要的 Stream 和 Consumer Group

-- 参数
-- KEYS[1]: 下行 Stream key (stream:down:gate:{gate_id})
-- KEYS[2]: 上行 Stream key (stream:up:game:{game_id})
-- ARGV[1]: Gate Consumer Group 名称
-- ARGV[2]: Game Consumer Group 名称

local down_stream = KEYS[1]
local up_stream = KEYS[2]
local gate_cg = ARGV[1]
local game_cg = ARGV[2]

-- 创建下行 Stream 和 Consumer Group
local gate_result = pcall(function()
    redis.call('XGROUP', 'CREATE', down_stream, gate_cg, '0', 'MKSTREAM')
end)

-- 创建上行 Stream 和 Consumer Group
local game_result = pcall(function()
    redis.call('XGROUP', 'CREATE', up_stream, game_cg, '0', 'MKSTREAM')
end)

return {
    gate_created = gate_result,
    game_created = game_result
}
