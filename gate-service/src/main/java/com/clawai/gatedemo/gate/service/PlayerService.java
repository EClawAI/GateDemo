package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 玩家服务（gRPC 版本）
 * 
 * 功能说明：
 * 1. 管理玩家连接（player_id ↔ Channel 映射）
 * 2. 发送消息给玩家
 * 3. 转发玩家消息到 Game 服务（通过 gRPC 长连接）
 * 
 * 与 HTTP 版本的区别：
 * - ✅ gRPC 长连接，避免每次握手开销
 * - ✅ HTTP/2 多路复用，并发更高
 * - ✅ Protobuf 序列化，比 JSON 更小更快
 * - ✅ 支持双向流心跳
 * 
 * 适用场景：
 * - 单机部署（Gate 和 Game 在同一台服务器）
 * - 多机部署（gRPC 支持负载均衡）
 * - 生产环境
 * 
 * 线程安全：
 * 使用 ConcurrentHashMap 保证多线程安全
 * 
 * @author clawAI
 * @since 2026-03-06
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
     * JSON 序列化工具
     * 用于将 Java 对象转换为 JSON 字符串，或反之
     */
    private final ObjectMapper objectMapper;

    /**
     * Gate 配置
     */
    private final GateConfig gateConfig;

    /**
     * gRPC 客户端连接池（用于调用 Game 服务）
     * 维护多个 Game 服务的长连接，根据 gameId 路由
     */
    private final GameGrpcClientPool gameGrpcClientPool;

    /**
     * 离线消息服务
     * 玩家离线时处理离线消息
     */
    private final OfflineMessageService offlineMessageService;

    /**
     * 玩家连接映射表（内存存储）
     * Key: player_id（玩家 ID）
     * Value: Channel（Netty 连接通道）
     * 
     * 使用 ConcurrentHashMap 保证线程安全
     * 支持高并发读写
     * 
     * 注意：
     * - 重启后数据会丢失（无持久化）
     * - 多实例部署时数据不共享
     */
    private final ConcurrentMap<Long, Channel> players = new ConcurrentHashMap<>();

    /**
     * 构造函数
     */
    public PlayerService(GateConfig gateConfig, ObjectMapper objectMapper, 
                       GameGrpcClientPool gameGrpcClientPool, 
                       OfflineMessageService offlineMessageService) {
        this.gateConfig = gateConfig;
        this.objectMapper = objectMapper;
        this.gameGrpcClientPool = gameGrpcClientPool;
        this.offlineMessageService = offlineMessageService;
    }

    /**
     * 注册玩家
     * 
     * 当玩家认证成功时调用
     * 
     * 功能：
     * 1. 将 player_id 与 Channel 绑定
     * 2. 存入本地内存映射表
     * 
     * 注意：
     * - 玩家在线状态存储在内存中
     * - Redis用于离线消息队列，不存储在线状态
     * - 重启后数据丢失
     * 
     * @param playerId 玩家 ID
     * @param channel Netty 通道
     */
    public void registerPlayer(Long playerId, Channel channel) {
        // 存入本地映射表
        players.put(playerId, channel);
        logger.info("📝 玩家 {} 已注册 (内存存储，当前在线：{})", playerId, players.size());
    }

    /**
     * 注销玩家
     * 
     * 当玩家断开连接时调用
     * 
     * 功能：
     * 1. 从本地映射表移除
     * 
     * 注意：
     * - 离线消息通过OfflineMessageService处理
     * 
     * @param playerId 玩家 ID
     */
    public void unregisterPlayer(Long playerId) {
        // 从本地映射表移除
        players.remove(playerId);
        
        // 通知离线消息服务
        if (offlineMessageService != null) {
            offlineMessageService.onPlayerOffline(playerId);
        }
        
        logger.info("🗑️ 玩家 {} 已注销 (当前在线：{})", playerId, players.size());
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
     * 心跳仅用于检测连接状态
     * 玩家在线状态存储在内存中
     * 
     * @param playerId 玩家 ID
     */
    public void renewHeartbeat(Long playerId) {
        logger.debug("💓 玩家 {} 心跳", playerId);
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
     * 转发玩家消息到 Game 服务（gRPC 方式）
     * 
     * 功能：
     * 1. 根据 gameId 路由到对应的 Game 服务
     * 2. 使用 gRPC 长连接发送消息
     * 3. Protobuf 序列化，高效传输
     * 4. Game 服务处理游戏逻辑
     * 
     * 与 HTTP 版本的区别：
     * - ✅ gRPC 长连接，避免每次握手开销
     * - ✅ Protobuf 序列化，比 JSON 更小更快
     * - ✅ 支持多 Game 服务实例
     * - ✅ 异步调用，不阻塞 Netty IO 线程
     * 
     * @param playerId 玩家 ID
     * @param gameId 游戏 ID
     * @param message 消息对象
     */
    public void forwardToGame(Long playerId, Integer gameId, PlayerMessage message) {
        if (gameId == null) {
            logger.warn("⚠️ 消息缺少 gameId，无法转发");
            return;
        }
        
        // 异步转发，不阻塞 Netty IO 线程
        CompletableFuture.runAsync(() -> {
            // 根据 gameId 使用连接池发送消息
            boolean success = gameGrpcClientPool.sendGameMessage(
                gameId,
                playerId,
                message.getMsgType() != null ? message.getMsgType() : "unknown",
                message.getSeq() != null ? message.getSeq().intValue() : 0,
                message.getBody() != null ? message.getBody() : Map.of()
            );
            
            if (success) {
                logger.debug("📤 gRPC 消息已转发到 Game: gameId={}, playerId={}", gameId, playerId);
            } else {
                logger.error("❌ gRPC 消息转发失败：gameId={}, playerId={}", gameId, playerId);
            }
        });
    }

    /**
     * 获取在线玩家数量
     * 
     * @return 在线玩家数
     */
    public int getOnlineCount() {
        return players.size();
    }
}