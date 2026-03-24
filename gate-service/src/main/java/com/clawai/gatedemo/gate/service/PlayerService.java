package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 玩家连接管理服务：维护 playerId ↔ Channel 映射，提供注册/注销/在线检测/下行发送。
 * <p>
 * 下行一律使用 {@link WrappedMessage}，由 Pipeline 中的编码器转为对应帧格式（Binary/TCP）。
 */
@Service
public class PlayerService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerService.class);

    public static final io.netty.util.AttributeKey<Long> PLAYER_ID_KEY =
        io.netty.util.AttributeKey.valueOf("playerId");

    private final GateConfig gateConfig;
    private final OfflineMessageService offlineMessageService;
    private final ConcurrentMap<Long, Channel> players = new ConcurrentHashMap<>();

    public PlayerService(GateConfig gateConfig,
                       @Lazy OfflineMessageService offlineMessageService) {
        this.gateConfig = gateConfig;
        this.offlineMessageService = offlineMessageService;
    }

    public void registerPlayer(Long playerId, Channel channel) {
        players.put(playerId, channel);
        logger.info("玩家 {} 已注册 (当前在线：{})", playerId, players.size());
    }

    public void unregisterPlayer(Long playerId) {
        players.remove(playerId);
        if (offlineMessageService != null) {
            offlineMessageService.onPlayerOffline(playerId);
        }
        logger.info("玩家 {} 已注销 (当前在线：{})", playerId, players.size());
    }

    public boolean hasPlayer(Long playerId) {
        return players.containsKey(playerId);
    }

    public Channel getPlayerChannel(Long playerId) {
        return players.get(playerId);
    }

    public void renewHeartbeat(Long playerId) {
        logger.debug("玩家 {} 心跳", playerId);
    }

    /**
     * 向指定玩家发送二进制协议消息。
     * Pipeline 中的编码器会将 {@link WrappedMessage} 转为 BinaryWebSocketFrame。
     *
     * @return true=写入成功，false=玩家不在线或连接已断开
     */
    public boolean sendToPlayer(Long playerId, WrappedMessage message) {
        Channel channel = players.get(playerId);

        if (channel == null) {
            logger.warn("玩家 {} 不在线，无法发送消息", playerId);
            return false;
        }

        if (!channel.isActive()) {
            logger.warn("玩家 {} 连接已断开", playerId);
            players.remove(playerId);
            return false;
        }

        ChannelFuture future = channel.writeAndFlush(message);
        future.addListener(f -> {
            if (!f.isSuccess()) {
                logger.error("消息发送失败：playerId={}, error={}", playerId,
                        f.cause() != null ? f.cause().getMessage() : "unknown");
            }
        });

        return true;
    }

    public int getOnlineCount() {
        return players.size();
    }

    public Set<Long> getAllOnlinePlayerIds() {
        return Collections.unmodifiableSet(players.keySet());
    }
}
