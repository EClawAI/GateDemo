package com.clawai.gatedemo.client.protocol.model;

import java.util.Map;

/**
 * 原始字节消息体：直接持有未解析的 byte[]，用于 protobuf 序列化后的二进制数据传输。
 */
public class RawMessageBody implements MessageBody {

    private byte[] data;

    public RawMessageBody() {
        this.data = new byte[0];
    }

    public RawMessageBody(byte[] data) {
        this.data = data != null ? data : new byte[0];
    }

    @Override
    public byte[] toBytes() {
        return data;
    }

    @Override
    public void fromBytes(byte[] bytes) {
        this.data = bytes != null ? bytes : new byte[0];
    }

    @Override
    public String toJson() {
        return "<raw " + data.length + " bytes>";
    }

    @Override
    public void fromJson(String json) {
        // raw body 不支持 JSON 反序列化
    }

    @Override
    public Map<String, Object> getData() {
        return null;
    }

    @Override
    public void setData(Map<String, Object> data) {
        // raw body 不支持 Map 操作
    }
}
