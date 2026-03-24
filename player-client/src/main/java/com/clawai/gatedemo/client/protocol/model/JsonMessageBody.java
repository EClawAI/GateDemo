package com.clawai.gatedemo.client.protocol.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 基于共享静态 {@link ObjectMapper} 的 JSON 体实现，在演示客户端内统一序列化/反序列化，避免多处配置漂移。
 */
public class JsonMessageBody implements MessageBody {

    /** 全类共享，避免多处 ObjectMapper 配置不一致 */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 结构化业务字段，序列化后即为协议体 */
    private Map<String, Object> data;

    public JsonMessageBody() {
    }

    /**
     * @param data 初始键值载荷，可为 null
     */
    public JsonMessageBody(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public byte[] toBytes() {
        if (data == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (JsonProcessingException e) {
            return new byte[0];
        }
    }

    @Override
    public void fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            this.data = null;
            return;
        }
        try {
            this.data = objectMapper.readValue(bytes, Map.class);
        } catch (Exception e) {
            this.data = null;
        }
    }

    @Override
    public String toJson() {
        if (data == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @Override
    public void fromJson(String json) {
        if (json == null || json.isEmpty()) {
            this.data = null;
            return;
        }
        try {
            this.data = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            this.data = null;
        }
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
