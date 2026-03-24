package com.clawai.gatedemo.game.service;

import java.util.Map;

/**
 * 流式场景下 Game 向 Gate 回推消息的回调；Unary RPC 无此出口时 echo/broadcast 等不会下发。
 */
@FunctionalInterface
public interface OutgoingMessageSink {

    /**
     * 发出一条下行游戏消息。Gate 侧约定：{@code playerId > 0} 单播给该玩家；{@code playerId == 0} 表示同游戏内广播。
     *
     * @param playerId 目标玩家，0 为广播
     * @param gameId   逻辑游戏 ID
     * @param msgType  消息类型，与上行协议一致
     * @param seq      与请求对齐的序列号
     * @param body     JSON 可序列化 Map，将经 Gate 转发给客户端
     */
    void emit(long playerId, int gameId, String msgType, int seq, Map<String, Object> body);
}
