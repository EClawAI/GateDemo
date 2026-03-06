package com.clawai.gatedemo.gate.protocol.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class JsonMessageBody implements MessageBody {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, Object> data;

    public JsonMessageBody() {
    }

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
