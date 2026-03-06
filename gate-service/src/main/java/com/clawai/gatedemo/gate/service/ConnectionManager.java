package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.model.PlayerConnection;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 连接管理器 - 管理所有玩家连接的核心服务
 *
 * 设计原理：
 * 游戏网关需要管理大量玩家连接，每个连接对应一个玩家。
 * 使用ConcurrentHashMap存储连接，实现线程安全的高并发访问。
 *
 * 核心功能：
 * 1. 连接注册：玩家连接时创建PlayerConnection记录
 * 2. 认证状态：区分已认证和未认证连接
 * 3. 心跳检测：定期检查连接是否存活
 * 4. 连接销毁：玩家断开时清理资源
 *
 * 为什么使用ConcurrentHashMap？
 * - 线程安全：多线程并发读写不需要额外同步
 * - 高性能：分段锁机制，读操作无锁
 * - 适合游戏场景：玩家频繁上下线
 *
 * 状态机设计：
 * CONNECTING → AUTHENTICATED → DISCONNECTED
 * - CONNECTING: 玩家连接但未完成认证
 * - AUTHENTICATED: 完成认证，可以收发消息
 * - DISCONNECTED: 连接已断开
 *
 * 心跳检测机制：
 * - 每30秒扫描一次所有连接
 * - 超过300秒（5分钟）未收到心跳则断开
 * - 使用ScheduledExecutorService实现定时任务
 */
@Service
public class ConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    /**
     * 连接存储：playerId -> PlayerConnection
     * 使用ConcurrentHashMap保证线程安全
     * Key: 玩家ID（Long类型）
     * Value: 玩家连接信息
     */
    private final Map<Long, PlayerConnection> connections = new ConcurrentHashMap<>();

    /**
     * 心跳检测调度器
     * 单线程定时执行心跳检查任务
     * 使用守护线程，避免阻止应用关闭
     */
    private final ScheduledExecutorService heartbeatChecker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat-checker");
        t.setDaemon(true);  // 守护线程：JVM退出时自动结束
        return t;
    });

    /** 心跳超时时间（秒），默认5分钟 */
    private long heartbeatTimeoutSeconds = 300;

    /** 最大连接数，防止服务器过载 */
    private int maxConnections = 10000;

    /**
     * 构造函数
     * 启动心跳检测定时任务
     */
    public ConnectionManager() {
        startHeartbeatChecker();
    }

    /**
     * 设置心跳超时时间
     * @param seconds 超时秒数
     */
    public void setHeartbeatTimeoutSeconds(long seconds) {
        this.heartbeatTimeoutSeconds = seconds;
    }

    /**
     * 设置最大连接数
     * @param max 最大连接数
     */
    public void setMaxConnections(int max) {
        this.maxConnections = max;
    }

    /**
     * 注册新连接
     *
     * 调用时机：玩家WebSocket连接建立时
     *
     * 流程：
     * 1. 检查连接数是否已满
     * 2. 创建PlayerConnection对象
     * 3. 存入ConcurrentHashMap
     * 4. 记录日志
     *
     * @param playerId 玩家ID
     * @param channel Netty通道
     */
    public void registerConnection(Long playerId, Channel channel) {
        // 检查连接数上限，防止DDoS攻击
        if (connections.size() >= maxConnections) {
            logger.warn("Max connections reached: {}", maxConnections);
            channel.close();  // 关闭连接
            return;
        }

        // 创建连接对象，初始状态为CONNECTING
        PlayerConnection conn = new PlayerConnection(playerId, channel);
        // 存入线程安全的Map
        connections.put(playerId, conn);
        
        logger.info("Player {} connected, total: {}", playerId, connections.size());
    }

    /**
     * 标记玩家认证成功
     *
     * 调用时机：玩家完成登录认证后
     *
     * 状态变更：CONNECTING → AUTHENTICATED
     *
     * @param playerId 玩家ID
     */
    public void authenticate(Long playerId) {
        PlayerConnection conn = connections.get(playerId);
        if (conn != null) {
            conn.setState(PlayerConnection.State.AUTHENTICATED);
            logger.info("Player {} authenticated", playerId);
        }
    }

    /**
     * 注销连接
     *
     * 调用时机：玩家断开连接时
     *
     * 流程：
     * 1. 从Map中移除连接
     * 2. 记录日志
     *
     * 注意：不需要手动关闭Channel，由Netty Handler处理
     *
     * @param playerId 玩家ID
     */
    public void unregisterConnection(Long playerId) {
        PlayerConnection removed = connections.remove(playerId);
        if (removed != null) {
            logger.info("Player {} disconnected, total: {}", playerId, connections.size());
        }
    }

    /**
     * 更新玩家心跳时间
     *
     * 调用时机：收到玩家心跳消息时
     *
     * 作用：重置心跳超时计时器
     *
     * @param playerId 玩家ID
     */
    public void renewHeartbeat(Long playerId) {
        PlayerConnection conn = connections.get(playerId);
        if (conn != null) {
            conn.updateHeartbeat();  // 更新最后心跳时间
        }
    }

    /**
     * 检查玩家是否在线且已认证
     *
     * 用于消息路由判断是否可以发送消息
     *
     * @param playerId 玩家ID
     * @return true表示玩家在线且已认证
     */
    public boolean hasPlayer(Long playerId) {
        PlayerConnection conn = connections.get(playerId);
        return conn != null && conn.getState() == PlayerConnection.State.AUTHENTICATED;
    }

    /**
     * 获取玩家对应的Channel
     *
     * 用于向玩家发送消息
     *
     * @param playerId 玩家ID
     * @return Channel对象，如果玩家不在线则返回null
     */
    public Channel getPlayerChannel(Long playerId) {
        PlayerConnection conn = connections.get(playerId);
        return conn != null ? conn.getChannel() : null;
    }

    /**
     * 获取当前在线玩家数量
     *
     * @return 在线玩家数
     */
    public int getOnlineCount() {
        return connections.size();
    }

    /**
     * 启动心跳检测任务
     *
     * 实现原理：
     * - 每30秒执行一次扫描
     * - 遍历所有已认证的连接
     * - 检查最后心跳时间是否超过阈值
     * - 超时的连接强制关闭
     *
     * 为什么用removeIf而不是手动遍历？
     * - 更简洁，Java 8特性
     * - 原子操作，无需担心并发问题
     */
    private void startHeartbeatChecker() {
        heartbeatChecker.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long timeout = heartbeatTimeoutSeconds * 1000;  // 转换为毫秒

            // 遍历并移除超时连接
            connections.entrySet().removeIf(entry -> {
                PlayerConnection conn = entry.getValue();
                
                // 只检查已认证的连接
                if (conn.getState() == PlayerConnection.State.AUTHENTICATED) {
                    // 检查是否超时
                    if (now - conn.getLastHeartbeatTime() > timeout) {
                        logger.warn("Player {} heartbeat timeout", conn.getPlayerId());
                        // 关闭Channel
                        if (conn.getChannel() != null) {
                            conn.getChannel().close();
                        }
                        return true;  // 移除该连接
                    }
                }
                return false;  // 保留该连接
            });
        }, 30, 30, TimeUnit.SECONDS);  // 首次延迟30秒，之后每30秒执行
    }

    /**
     * 关闭管理器
     *
     * 应用停止时调用，清理资源
     */
    public void shutdown() {
        heartbeatChecker.shutdown();
    }
}
