package com.clawai.gatedemo.client.protocol.model;

/**
 * 16 字节二进制帧头在客户端侧的镜像：承载标志位、序号、消息 ID、体长与请求 ID。
 */
public class MessageHeader {

    private short flags;
    private short sequence;
    private int messageId;
    private int bodyLength;
    private int requestId;

    public static final int FLAG_COMPRESSED = 0x8000;
    public static final int FLAG_ENCRYPTED = 0x4000;
    public static final int FLAG_MASK_MODE = 0x00C0;

    public static final short MODE_REQUEST = 0x0000;
    public static final short MODE_RESPONSE = 0x0040;
    public static final short MODE_PUSH = 0x0080;

    public MessageHeader() {
    }

    public MessageHeader(int messageId) {
        this.messageId = messageId;
    }

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

    public short getFlags() { return flags; }
    public void setFlags(short flags) { this.flags = flags; }

    public short getSequence() { return sequence; }
    public void setSequence(short sequence) { this.sequence = sequence; }

    public int getMessageId() { return messageId; }
    public void setMessageId(int messageId) { this.messageId = messageId; }

    public int getBodyLength() { return bodyLength; }
    public void setBodyLength(int bodyLength) { this.bodyLength = bodyLength; }

    public int getRequestId() { return requestId; }
    public void setRequestId(int requestId) { this.requestId = requestId; }

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
