package com.clawai.gatedemo.gate.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * WebSocket 侧 JSON 消息载体，与客户端约定 type（如鉴权、心跳、游戏业务）及 seq/body 等字段，便于网关解析后转发或落队。
 */
public class PlayerMessage {

    @JsonProperty("type")
    private String type;

    @JsonProperty("player_id")
    private Long playerId;

    @JsonProperty("game_id")
    private Integer gameId;

    @JsonProperty("msg_type")
    private String msgType;

    @JsonProperty("seq")
    private Long seq;

    @JsonProperty("body")
    private Map<String, Object> body;

    @JsonProperty("timestamp")
    private Long timestamp;

    // Getters and Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }
    public Integer getGameId() { return gameId; }
    public void setGameId(Integer gameId) { this.gameId = gameId; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public Map<String, Object> getBody() { return body; }
    public void setBody(Map<String, Object> body) { this.body = body; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    /** 调试输出主要字段，不含完整 body。 */
    @Override
    public String toString() {
        return "PlayerMessage{" +
                "type='" + type + '\'' +
                ", playerId=" + playerId +
                ", gameId=" + gameId +
                ", msgType='" + msgType + '\'' +
                ", seq=" + seq +
                '}';
    }
}