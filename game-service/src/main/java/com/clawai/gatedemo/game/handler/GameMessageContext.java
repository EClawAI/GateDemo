package com.clawai.gatedemo.game.handler;

import com.clawai.gatedemo.core.message.MessageContext;
import com.clawai.gatedemo.game.model.PlayerData;

/**
 * Game 服务消息处理上下文。在 core 基础上扩展：
 * <ul>
 *   <li>{@link #player} — 由 Dispatcher 前置加载</li>
 *   <li>{@link #dirty} — handler 中调用 {@link #markDirty()} 标记需要存盘</li>
 * </ul>
 */
public class GameMessageContext extends MessageContext {

    private PlayerData player;
    private boolean dirty;

    public PlayerData getPlayer() { return player; }
    public void setPlayer(PlayerData player) { this.player = player; }

    public boolean isDirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
}
