package com.clawai.gatedemo.gate.protocol.model;

import java.util.Map;

/**
 * 消息体抽象：与固定长度消息头配合，承载可变长负载；便于替换 JSON / Protobuf 等编码。
 *
 * @see JsonMessageBody 默认 JSON 实现
 */
public interface MessageBody {

    /** @return 供链路上传输的字节序列（如 JSON UTF-8） */
    byte[] toBytes();

    /** @param bytes 对端发来的原始负载；实现应覆盖或填充内部状态 */
    void fromBytes(byte[] bytes);

    /** @return 便于日志与调试的 JSON 文本 */
    String toJson();

    /** @param json 文本形态负载 */
    void fromJson(String json);

    Map<String, Object> getData();

    void setData(Map<String, Object> data);
}
