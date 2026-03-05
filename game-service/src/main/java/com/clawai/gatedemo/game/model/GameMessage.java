package com.clawai.gatedemo.game.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class GameMessage {

    @JsonProperty("seq")
    private Long seq;

    @JsonProperty("msg_type")
    private String msgType;

    @JsonProperty("body")
    private Map<String, Object> body;

    @JsonProperty("timestamp")
    private Long timestamp;

    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    public Map<String, Object> getBody() { return body; }
    public void setBody(Map<String, Object> body) { this.body = body; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "GameMessage{" +
                "seq=" + seq +
                ", msgType='" + msgType + '\'' +
                ", body=" + body +
                ", timestamp=" + timestamp +
                '}';
    }
}