package com.clawai.gatedemo.core.message;

import com.google.protobuf.MessageLite;

/**
 * 消息处理基础上下文，携带本次请求的玩家、游戏、消息元信息和下行发送器。
 * 后端服务可继承此类扩展服务特有的上下文（如 GameMessageContext 添加 PlayerData）。
 */
public class MessageContext {

    private long playerId;
    private int gameId;
    private int messageId;
    private int sequence;
    private MessageSender sender;

    public long getPlayerId() { return playerId; }
    public void setPlayerId(long playerId) { this.playerId = playerId; }

    public int getGameId() { return gameId; }
    public void setGameId(int gameId) { this.gameId = gameId; }

    public int getMessageId() { return messageId; }
    public void setMessageId(int messageId) { this.messageId = messageId; }

    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }

    public MessageSender getSender() { return sender; }
    public void setSender(MessageSender sender) { this.sender = sender; }
}
