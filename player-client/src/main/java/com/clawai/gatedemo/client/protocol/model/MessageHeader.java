package com.clawai.gatedemo.client.protocol.model;

/**
 * 14 字节二进制帧头在客户端侧的镜像：承载标志位、序号、消息 ID、体长与请求 ID，并提供压缩/模式等语义辅助。
 */
public class MessageHeader {

    private short flags;
    private short sequence;
    private short messageId;
    private int bodyLength;
    private int requestId;

    /** 体经 zlib 等压缩时置位，与解码侧解压逻辑对应 */
    public static final int FLAG_COMPRESSED = 0x8000;
    /** 预留加密标志，当前演示客户端未实现加解密 */
    public static final int FLAG_ENCRYPTED = 0x4000;
    /** 请求/响应/推送模式占用的 flags 位段 */
    public static final int FLAG_MASK_MODE = 0x00C0;

    public static final short MODE_REQUEST = 0x0000;
    public static final short MODE_RESPONSE = 0x0040;
    public static final short MODE_PUSH = 0x0080;

    public MessageHeader() {
    }

    /**
     * @param messageId 业务短消息 ID，其余字段默认零
     */
    public MessageHeader(short messageId) {
        this.messageId = messageId;
    }

    /** @return 是否携带压缩标志位 */
    public boolean isCompressed() {
        return (flags & FLAG_COMPRESSED) != 0;
    }

    public void setCompressed(boolean compressed) {
        if (compressed) {
            flags |= FLAG_COMPRESSED;
        } else {
            flags &= ~FLAG_COMPRESSED;
        }
    }

    public boolean isEncrypted() {
        return (flags & FLAG_ENCRYPTED) != 0;
    }

    public void setEncrypted(boolean encrypted) {
        if (encrypted) {
            flags |= FLAG_ENCRYPTED;
        } else {
            flags &= ~FLAG_ENCRYPTED;
        }
    }

    public short getMode() {
        return (short) (flags & FLAG_MASK_MODE);
    }

    public void setMode(short mode) {
        flags = (short) ((flags & ~FLAG_MASK_MODE) | (mode & FLAG_MASK_MODE));
    }

    public short getFlags() {
        return flags;
    }

    public void setFlags(short flags) {
        this.flags = flags;
    }

    public short getSequence() {
        return sequence;
    }

    public void setSequence(short sequence) {
        this.sequence = sequence;
    }

    public short getMessageId() {
        return messageId;
    }

    public void setMessageId(short messageId) {
        this.messageId = messageId;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }

    public int getRequestId() {
        return requestId;
    }

    public void setRequestId(int requestId) {
        this.requestId = requestId;
    }

    @Override
    public String toString() {
        return "MessageHeader{" +
                "flags=" + flags +
                ", sequence=" + sequence +
                ", messageId=" + messageId +
                ", bodyLength=" + bodyLength +
                ", requestId=" + requestId +
                '}';
    }
}
