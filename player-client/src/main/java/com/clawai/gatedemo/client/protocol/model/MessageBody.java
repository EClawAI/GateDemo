package com.clawai.gatedemo.client.protocol.model;

import java.util.Map;

/**
 * 客户端消息体的可序列化契约：二进制与 JSON 双形态及结构化 {@code Map} 访问，由编解码层与业务载荷共用。
 */
public interface MessageBody {

    /** @return 当前载荷的 JSON 二进制编码，无数据可为空数组 */
    byte[] toBytes();

    /**
     * 从二进制还原内部结构；解析失败实现类可自行置空或保持旧值。
     *
     * @param bytes 协议体字节
     */
    void fromBytes(byte[] bytes);

    /** @return JSON 文本，便于日志与调试 */
    String toJson();

    /**
     * 从 JSON 文本填充载荷。
     *
     * @param json JSON 字符串
     */
    void fromJson(String json);

    Map<String, Object> getData();

    void setData(Map<String, Object> data);
}
