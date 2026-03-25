package com.clawai.gatedemo.core.message;

import com.google.protobuf.MessageLite;

/**
 * 下行消息发送接口。实现方负责从 message 的 class simpleName 查 messageId，
 * 将 message 序列化为 protobuf 二进制后通过传输层（如 gRPC）回送 Gate。
 */
public interface MessageSender {

    /**
     * 单播：向指定玩家发送下行消息。
     *
     * @param playerId 目标玩家
     * @param message  protobuf 消息对象，框架从 getClass() 推导 messageId
     */
    void send(long playerId, MessageLite message);

    /**
     * 广播：向同游戏内所有玩家广播消息。
     *
     * @param gameId  目标游戏实例
     * @param message protobuf 消息对象
     */
    void broadcast(int gameId, MessageLite message);
}
