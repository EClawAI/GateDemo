package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 玩家服务
 * 
 * 功能说明：
 * 1. 管理玩家连接（player_id ↔ Channel 映射）
 * 2. 维护 Player-Gate 映射表（Redis）
 * 3. 发送消息给玩家
 * 4. 转发玩家消息到 Game 服务
 * 
 * 线程安全：
 * 使用 ConcurrentHashMap 保证多线程安全
 * 
 * @author clawAI
 * @since 2026-03-05
 */
@Service
public class PlayerService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerService.class);

    /**
     * Channel Attribute Key
     * 用于在 Channel 中存储 player_id
     * 
     * 使用方式：
     * channel.attr(PlayerService.PLAYER_ID_KEY).set(playerId);  // 存储
     * channel.attr(PlayerService.PLAYER_ID_KEY).get();          // 获取
     */
    public static final io.netty.util.AttributeKey<Long> PLAYER_ID_KEY = 
        io.netty.util.AttributeKey.valueOf("playerId");

    /**
     * Gate 配置（由 Spring 自动注入）
     * 包含 Gate ID、Redis 配置、玩家配置等
     */
    private final GateConfig gateConfig;

    /**
     * Redis 命令接口（由 Spring 自动注入）
     * 提供响应式的 Redis 操作
     */
    private final RedisReactiveCommands<String, String> redisCommands;

    /**
     * JSON 序列化工具（由 Spring 自动注入）
     */
    private final ObjectMapper objectMapper;

    /**
     * 玩家连接映射表
     * Key: player_id（玩家 ID）
     * Value: Channel（Netty 连接通道）
     * 
     * 使用 ConcurrentHashMap 保证线程安全
     * 支持高并发读写
     */
    private final ConcurrentMap<Long, Channel> players = new ConcurrentHashMap<>();

    /**
     * 构造函数，注入依赖
     */
    public PlayerService(GateConfig gateConfig, 
                        RedisReactiveCommands<String, String> redisCommands, 
                        ObjectMapper objectMapper) {
        this.gateConfig = gateConfig;
        this.redisCommands = redisCommands;
        this.objectMapper = objectMapper;
    }

    /**
     * 注册玩家
     * 
     * 当玩家认证成功时调用
     * 
     * 功能：
     * 1. 将 player_id 与 Channel 绑定
     * 2. 在 Redis 中记录 Player-Gate 映射
     * 3. 设置 TTL（过期时间）
     * 
     * @param playerId 玩家 ID
     * @param channel Netty 通道
     */
    public void registerPlayer(Long playerId, Channel channel) {
        // 1. 存入本地映射表
        players.put(playerId, channel);
        logger.info("📝 玩家 {} 已注册到本地映射表", playerId);

        // 2. 注册到 Redis
        // Key: player:gate:{player_id}
        // Value: {gate_id}
        // TTL: 300 秒（5 分钟）
        String key = "player:gate:" + playerId;
        int ttl = gateConfig.getPlayer().getMapTtl();
        
        redisCommands.setex(key, ttl, gateConfig.getId())
            .subscribe(
                result -> logger.info("✅ 玩家 {} 已注册到 Redis (Gate={}, TTL={}s)", playerId, gateConfig.getId(), ttl),
                error -> logger.error("❌ Redis 注册失败：{}", error.getMessage())
            );
    }

    /**
     * 注销玩家
     * 
     * 当玩家断开连接时调用
     * 
     * 功能：
     * 1. 从本地映射表移除
     * 2. 从 Redis 删除 Player-Gate 映射
     * 
     * @param playerId 玩家 ID
     */
    public void unregisterPlayer(Long playerId) {
        // 1. 从本地映射表移除
        players.remove(playerId);
        logger.debug("🗑️ 玩家 {} 已从本地映射表移除", playerId);

        // 2. 从 Redis 删除
        String key = "player:gate:" + playerId;
        redisCommands.del(key)
            .subscribe(
                deleted -> logger.info("✅ 玩家 {} 已从 Redis 注销", playerId),
                error -> logger.error("❌ Redis 注销失败：{}", error.getMessage())
            );
    }

    /**
     * 检查玩家是否在线
     * 
     * @param playerId 玩家 ID
     * @return true=在线，false=离线
     */
    public boolean hasPlayer(Long playerId) {
        return players.containsKey(playerId);
    }

    /**
     * 获取玩家的 Channel
     * 
     * @param playerId 玩家 ID
     * @return Channel，如果玩家不在线则返回 null
     */
    public Channel getPlayerChannel(Long playerId) {
        return players.get(playerId);
    }

    /**
     * 续期心跳
     * 
     * 当收到玩家心跳消息时调用
     * 
     * 功能：
     * 刷新 Redis 中 Player-Gate 映射的 TTL
     * 防止映射过期导致玩家掉线
     * 
     * @param playerId 玩家 ID
     */
    public void renewHeartbeat(Long playerId) {
        String key = "player:gate:" + playerId;
        int ttl = gateConfig.getPlayer().getMapTtl();
        
        redisCommands.expire(key, ttl)
            .subscribe(
                renewed -> logger.debug("💓 玩家 {} 心跳续期 (TTL={}s)", playerId, ttl),
                error -> logger.warn("⚠️ 心跳续期失败：{}", error.getMessage())
            );
    }

    /**
     * 发送消息给玩家
     * 
     * 功能：
     * 1. 查找玩家的 Channel
     * 2. 将 PlayerMessage 转换为 JSON
     * 3. 封装为 TextWebSocketFrame
     * 4. 发送到客户端
     * 
     * @param playerId 玩家 ID
     * @param message 消息对象
     * @return true=发送成功，false=发送失败
     */
    public boolean sendToPlayer(Long playerId, PlayerMessage message) {
        // 1. 获取玩家的 Channel
        Channel channel = players.get(playerId);
        
        if (channel == null) {
            logger.warn("⚠️ 玩家 {} 不在线，无法发送消息", playerId);
            return false;
        }

        // 2. 检查连接是否活跃
        if (!channel.isActive()) {
            logger.warn("⚠️ 玩家 {} 连接已断开", playerId);
            players.remove(playerId);  // 清理映射
            return false;
        }

        try {
            // 3. 将对象转换为 JSON 字符串
            String json = objectMapper.writeValueAsString(message);
            
            // 4. 封装为 WebSocket 文本帧
            TextWebSocketFrame frame = new TextWebSocketFrame(json);
            
            // 5. 发送消息
            // writeAndFlush 会异步发送，返回 ChannelFuture
            ChannelFuture future = channel.writeAndFlush(frame);
            
            // 6. 监听发送结果（可选）
            future.addListener(f -> {
                if (f.isSuccess()) {
                    logger.debug("✅ 消息发送成功：playerId={}, type={}", playerId, message.getMsgType());
                } else {
                    logger.error("❌ 消息发送失败：playerId={}, error={}", playerId, f.cause().getMessage());
                }
            });
            
            return true;
            
        } catch (JsonProcessingException e) {
            logger.error("❌ JSON 序列化失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 转发玩家消息到 Game 服务
     * 
     * 功能：
     * 1. 将消息写入 Redis Stream（上行）
     * 2. Game 服务消费 Stream 处理游戏逻辑
     * 
     * Stream 结构：
     * Key: stream:up:game:{game_id}
     * Fields:
     *   - gate_id: 网关 ID
     *   - player_id: 玩家 ID
     *   - msg_type: 消息类型
     *   - seq: 序列号
     *   - timestamp: 时间戳
     *   - body: 消息体（JSON）
     * 
     * @param playerId 玩家 ID
     * @param gameId 游戏 ID
     * @param message 消息对象
     */
    public void forwardToGame(Long playerId, Integer gameId, PlayerMessage message) {
        // 1. 构建 Stream Key
        String streamKey = "stream:up:game:" + gameId;

        // 2. 构建消息字段
        Map<String, String> fields = Map.of(
            "gate_id", gateConfig.getId(),
            "player_id", String.valueOf(playerId),
            "msg_type", message.getMsgType() != null ? message.getMsgType() : "unknown",
            "seq", String.valueOf(message.getSeq() != null ? message.getSeq() : 0),
            "timestamp", String.valueOf(System.currentTimeMillis()),
            "body", toJson(message.getBody())
        );

        // 3. 写入 Redis Stream
        // xadd 返回消息 ID（如：1612345678901-0）
        redisCommands.xadd(streamKey, fields)
            .subscribe(
                msgId -> logger.debug("📤 消息已转发到 Game: gameId={}, playerId={}, msgId={}", 
                    gameId, playerId, msgId),
                error -> logger.error("❌ 转发消息失败：{}", error.getMessage())
            );
    }

    /**
     * 获取在线玩家数量
     * 
     * @return 在线玩家数
     */
    public int getOnlineCount() {
        return players.size();
    }

    /**
     * 对象转 JSON 字符串（辅助方法）
     * 
     * @param obj 对象
     * @return JSON 字符串，失败返回 "{}"
     */
    private String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.warn("⚠️ JSON 序列化失败：{}", e.getMessage());
            return "{}";
        }
    }
}