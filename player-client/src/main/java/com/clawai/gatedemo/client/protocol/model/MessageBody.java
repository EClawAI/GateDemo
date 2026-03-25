package com.clawai.gatedemo.client.protocol.model;

/**
 * 消息体抽象：与固定长度消息头配合，承载可变长二进制负载（protobuf bytes）。
 *
 * @see RawMessageBody 默认实现
 */
public interface MessageBody {

    /** @return 供链路传输的字节序列（protobuf 二进制） */
    byte[] toBytes();

    /** @param bytes 对端发来的原始负载 */
    void fromBytes(byte[] bytes);
}
