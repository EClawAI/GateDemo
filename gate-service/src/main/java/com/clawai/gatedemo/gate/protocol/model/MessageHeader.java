package com.clawai.gatedemo.gate.protocol.model;

/**
 * 消息头模型 - 游戏网关通信协议的核心数据结构
 *
 * 字节布局（16字节）：
 * | 2字节   | 2字节   | 4字节     | 4字节    | 4字节    |
 * | flags   | sequence| messageId | bodyLength| requestId|
 *
 * 字段说明：
 * - flags: 标志位，包含压缩、加密、消息模式（请求/响应/推送）
 * - sequence: 消息序列号，用于消息排序和追踪
 * - messageId: 消息ID，由消息名哈希计算得出（CRC32 值域）
 * - bodyLength: 消息体长度
 * - requestId: 请求ID，用于关联请求和响应
 *
 * 消息模式：
 * - MODE_REQUEST (0x0000): 客户端请求，期望服务器响应
 * - MODE_RESPONSE (0x0040): 服务器响应，与请求的requestId对应
 * - MODE_PUSH (0x0080): 服务器主动推送，无需响应
 */
public class MessageHeader {

    private short flags;
    private short sequence;
    private int messageId;
    private int bodyLength;
    private int requestId;
    /**
     * 网关下行单调序号。仅当 {@link #FLAG_HAS_GW_SEQ} 置位时通过线缆传输。
     * 0 表示「未携带 gwSeq」，与协议默认值一致。
     */
    private long gwSeq;

    public static final int FLAG_COMPRESSED = 0x8000;
    public static final int FLAG_ENCRYPTED = 0x4000;
    /**
     * 置位表示帧体在 16 字节标准头之后、{@code bodyLength} 字节 body 之前，多 8 字节 big-endian
     * unsigned 64-bit {@code gwSeq}。仅当客户端在 AUTH 阶段声明
     * {@code client_features.supports_gw_seq} 时，服务端才会 stamp 该位。详见
     * {@code openspec/changes/add-flow-downstream-buffer/design.md} §2。
     */
    public static final int FLAG_HAS_GW_SEQ = 0x2000;
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

    public boolean hasGwSeq() {
        return (flags & FLAG_HAS_GW_SEQ) != 0;
    }

    public void setHasGwSeq(boolean hasGwSeq) {
        if (hasGwSeq) {
            flags |= FLAG_HAS_GW_SEQ;
        } else {
            flags &= ~FLAG_HAS_GW_SEQ;
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

    public long getGwSeq() { return gwSeq; }
    public void setGwSeq(long gwSeq) { this.gwSeq = gwSeq; }

    @Override
    public String toString() {
        String modeStr = switch (getMode()) {
            case MODE_REQUEST -> "REQUEST";
            case MODE_RESPONSE -> "RESPONSE";
            case MODE_PUSH -> "PUSH";
            default -> "UNKNOWN";
        };
        return String.format("MessageHeader[flags=0x%04X, seq=%d, msgId=%d, bodyLen=%d, reqId=%d, gwSeq=%d, mode=%s]",
                flags & 0xFFFF, sequence & 0xFFFF, messageId, bodyLength, requestId, gwSeq, modeStr);
    }
}
