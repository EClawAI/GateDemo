package com.clawai.gatedemo.client.protocol.model;

import java.util.Map;

/**
 * 客户端消息体的可序列化契约：二进制与 JSON 双形态及结构化 {@code Map} 访问，由编解码层与业务载荷共用。
 */
public interface MessageBody {

    byte[] toBytes();

    void fromBytes(byte[] bytes);

    String toJson();

    void fromJson(String json);

    Map<String, Object> getData();

    void setData(Map<String, Object> data);
}
