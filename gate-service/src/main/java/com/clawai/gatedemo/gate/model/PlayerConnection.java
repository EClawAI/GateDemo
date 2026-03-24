package com.clawai.gatedemo.gate.model;

import io.netty.channel.Channel;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 表示单条玩家 TCP 连接在网关内的会话视图：关联 Netty Channel、心跳时间与认证状态，供连接管理与路由决策使用。
 */
public class PlayerConnection {

    private final Long playerId;
    private final Channel channel;
    private final long connectTime;
    private final AtomicLong lastHeartbeatTime;
    private volatile State state;

    public enum State {
        CONNECTING, AUTHENTICATED, DISCONNECTED
    }

    public PlayerConnection(Long playerId, Channel channel) {
        this.playerId = playerId;
        this.channel = channel;
        this.connectTime = System.currentTimeMillis();
        this.lastHeartbeatTime = new AtomicLong(System.currentTimeMillis());
        this.state = State.CONNECTING;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public Channel getChannel() {
        return channel;
    }

    public long getConnectTime() {
        return connectTime;
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime.get();
    }

    public void updateHeartbeat() {
        lastHeartbeatTime.set(System.currentTimeMillis());
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }
}
