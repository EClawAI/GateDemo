package com.clawai.gatedemo.gate.protocol.model;

/**
 * 原始字节消息体：直接持有未解析的 byte[]，
 * 用于 gate 不解析 body 的场景（转发 protobuf 二进制等）。
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
}
