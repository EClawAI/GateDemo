package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.flow.FlowSession;
import com.clawai.gatedemo.gate.flow.FlowSessionManager;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 玩家「下行 + 在线」对外门面，<b>不再</b>直接持有 {@code playerId → Channel} 映射。
 *
 * <p>内部委托 {@link FlowSessionManager}：
 * <ul>
 *   <li>{@link #sendToPlayer} 沿 ATTACHED FlowSession 找当前 Channel；DETACHED / 不存在视为离线；</li>
 *   <li>{@link #hasPlayer} 仅返回「ATTACHED 且 Channel 活跃」状态；区分弱网瞬断与真离线由
 *       {@link FlowSessionManager} 内部状态机决定；</li>
 *   <li>{@link #unregisterPlayer} 由 handler 在「玩家彻底下线」时调用（顶号 / 强制断），
 *       触发 {@link OfflineMessageService#onPlayerOffline}；普通 Channel 关闭走 RESUME 等待窗口，
 *       不应直接调本方法。</li>
 * </ul>
 *
 * <p>历史方法 {@link #registerPlayer} / {@link #getPlayerChannel} 仍存以兼容老代码，但
 * 在新 AUTH 路径下应改走 {@link FlowSessionManager#newFlow}。
 */
@Service
public class PlayerService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerService.class);

    public static final io.netty.util.AttributeKey<Long> PLAYER_ID_KEY =
        io.netty.util.AttributeKey.valueOf("playerId");

    private final OfflineMessageService offlineMessageService;
    private final FlowSessionManager flowSessionManager;

    public PlayerService(@Lazy OfflineMessageService offlineMessageService,
                         FlowSessionManager flowSessionManager) {
        this.offlineMessageService = offlineMessageService;
        this.flowSessionManager = flowSessionManager;
    }

    /**
     * 兼容旧 API：仅做 Channel attribute 标记；真正的注册由
     * {@link FlowSessionManager#newFlow(long, int, Channel)} 完成。
     */
    public void registerPlayer(Long playerId, Channel channel) {
        if (playerId == null || channel == null) return;
        channel.attr(PLAYER_ID_KEY).set(playerId);
        logger.debug("玩家 {} 已注册（当前在线 attached={}）",
                playerId, flowSessionManager.attachedPlayerIds().size());
    }

    /**
     * 显式注销（顶号 / 强制断）。Channel 关闭走 RESUME 窗口，<b>不</b>应调用本方法。
     */
    public void unregisterPlayer(Long playerId) {
        if (playerId == null) return;
        FlowSession session = flowSessionManager.getByPlayerId(playerId);
        if (session != null) {
            flowSessionManager.destroy(session, "unregister");
        }
        if (offlineMessageService != null) {
            offlineMessageService.onPlayerOffline(playerId);
        }
        logger.info("玩家 {} 已注销（当前在线 attached={}）",
                playerId, flowSessionManager.attachedPlayerIds().size());
    }

    /** ATTACHED 且 Channel 活跃才视为在线（区别于 DETACHED 等待 RESUME）。 */
    public boolean hasPlayer(Long playerId) {
        return playerId != null && flowSessionManager.hasAttachedChannel(playerId);
    }

    /**
     * 返回当前 ATTACHED 的 Channel；DETACHED / 不存在返回 {@code null}。
     */
    public Channel getPlayerChannel(Long playerId) {
        if (playerId == null) return null;
        FlowSession session = flowSessionManager.getByPlayerId(playerId);
        if (session == null || session.getState() != FlowSession.State.ATTACHED) return null;
        Channel ch = session.getCurrentChannel();
        return (ch != null && ch.isActive()) ? ch : null;
    }

    public void renewHeartbeat(Long playerId) {
        logger.debug("玩家 {} 心跳", playerId);
    }

    /**
     * 向指定玩家发送二进制协议消息（B3 起按 FlowSession 状态分派）：
     *
     * <ul>
     *   <li>ATTACHED + active Channel → {@link FlowSessionManager#writeDownstream}（B1 buffer + 直写）；</li>
     *   <li>DETACHED（session 仍在本地）→ {@link OfflineMessageService#storeForDetached}：
     *       stamp gwSeq 后写入 flowId 隔离 stream，RESUME 时合流投递；</li>
     *   <li>无 FlowSession → {@link OfflineMessageService#storeForOffline}：写入 playerId 兜底 stream，
     *       下次 NEW 首登 / 触发 RELOGIN 时处理。</li>
     * </ul>
     *
     * @return 是否被本地或离线 store 接管（true = 链路上至少有一处持有该消息；false = 完全丢失）
     */
    public boolean sendToPlayer(Long playerId, WrappedMessage message) {
        if (playerId == null) return false;
        FlowSession session = flowSessionManager.getByPlayerId(playerId);
        if (session == null) {
            if (offlineMessageService == null) {
                logger.warn("玩家 {} 无 FlowSession 且离线服务未就绪，下行消息丢失", playerId);
                return false;
            }
            return offlineMessageService.storeForOffline(playerId, message);
        }
        boolean attached = session.getState() == FlowSession.State.ATTACHED
                && session.getCurrentChannel() != null
                && session.getCurrentChannel().isActive();
        if (attached) {
            return flowSessionManager.writeDownstream(session, message);
        }
        if (offlineMessageService == null) {
            logger.warn("玩家 {} DETACHED 但离线服务未就绪，下行消息丢失", playerId);
            return false;
        }
        return offlineMessageService.storeForDetached(session, message);
    }

    public int getOnlineCount() {
        return flowSessionManager.attachedPlayerIds().size();
    }

    public Set<Long> getAllOnlinePlayerIds() {
        return flowSessionManager.attachedPlayerIds();
    }
}
