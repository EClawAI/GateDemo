package com.clawai.gatedemo.gate.protocol.model;

/**
 * 消息头模型 - 游戏网关通信协议的核心数据结构
 *
 * 设计原理：
 * 游戏通信需要高效的数据传输，因此采用二进制协议而非JSON。
 * 消息头固定14字节，包含所有元数据，解析效率高。
 *
 * 字节布局（14字节）：
 * | 2字节   | 2字节   | 2字节   | 4字节    | 4字节    |
 * | flags   | sequence| messageId| bodyLength| requestId|
 *
 * 字段说明：
 * - flags: 标志位，包含压缩、加密、消息模式（请求/响应/推送）
 * - sequence: 消息序列号，用于消息排序和追踪
 * - messageId: 消息ID，标识消息类型（使用FNV哈希算法生成）
 * - bodyLength: 消息体长度
 * - requestId: 请求ID，用于关联请求和响应
 *
 * 消息模式：
 * - MODE_REQUEST (0x0000): 客户端请求，期望服务器响应
 * - MODE_RESPONSE (0x0040): 服务器响应，与请求的requestId对应
 * - MODE_PUSH (0x0080): 服务器主动推送，无需响应
 *
 * 位运算说明：
 * - 使用位运算（&, |）操作flags字段，节省空间
 * - FLAG_COMPRESSED (0x8000): 第15位，表示消息体是否压缩
 * - FLAG_ENCRYPTED (0x4000): 第14位，表示消息体是否加密
 * - FLAG_MASK_MODE (0x00C0): 第7-8位，提取消息模式
 */
public class MessageHeader {

    /**
     * 标志位字段
     * bit 15: 压缩标志 (0x8000 = 1000 0000 0000 0000)
     * bit 14: 加密标志 (0x4000 = 0100 0000 0000 0000)
     * bit 7-8: 消息模式 (0x00C0 = 0000 0000 1100 0000)
     */
    private short flags;

    /**
     * 消息序列号
     * 用于消息排序、重复检测、追踪请求-响应对应关系
     * 范围: 0-65535，循环使用
     */
    private short sequence;

    /**
     * 消息ID
     * 标识消息类型，使用FNV哈希算法从消息名称生成
     * 例如: "auth.login" -> 0x1001, "heartbeat" -> 0x2001
     */
    private short messageId;

    /**
     * 消息体长度
     * 单位：字节
     * 用于边界检测和内存分配
     */
    private int bodyLength;

    /**
     * 请求ID
     * 客户端生成，服务器响应时携带相同的requestId
     * 用于关联请求和响应，支持异步消息处理
     */
    private int requestId;

    // ==================== 标志位常量 ====================

    /** 压缩标志：消息体使用DEFLATE算法压缩 */
    public static final int FLAG_COMPRESSED = 0x8000;

    /** 加密标志：消息体使用AES加密 */
    public static final int FLAG_ENCRYPTED = 0x4000;

    /** 模式掩码：用于提取消息模式 */
    public static final int FLAG_MASK_MODE = 0x00C0;

    // ==================== 消息模式常量 ====================

    /** 请求模式：客户端发起请求，期望服务器响应 */
    public static final short MODE_REQUEST = 0x0000;

    /** 响应模式：服务器响应客户端请求 */
    public static final short MODE_RESPONSE = 0x0040;

    /** 推送模式：服务器主动推送消息，无需客户端响应 */
    public static final short MODE_PUSH = 0x0080;

    // ==================== 构造函数 ====================

    /** 默认构造函数 */
    public MessageHeader() {
    }

    /**
     * 带消息ID的构造函数
     * @param messageId 消息ID
     */
    public MessageHeader(short messageId) {
        this.messageId = messageId;
    }

    // ==================== 压缩/加密标志方法 ====================

    /**
     * 检查消息是否被压缩
     * @return true表示已压缩，读取时需要解压
     */
    public boolean isCompressed() {
        return (flags & FLAG_COMPRESSED) != 0;
    }

    /**
     * 设置压缩标志
     * 压缩条件：消息体大于64字节
     * @param compressed 是否压缩
     */
    public void setCompressed(boolean compressed) {
        if (compressed) {
            flags |= FLAG_COMPRESSED;
        } else {
            flags &= ~FLAG_COMPRESSED;
        }
    }

    /**
     * 检查消息是否被加密
     * @return true表示已加密，读取时需要解密
     */
    public boolean isEncrypted() {
        return (flags & FLAG_ENCRYPTED) != 0;
    }

    /**
     * 设置加密标志
     * @param encrypted 是否加密
     */
    public void setEncrypted(boolean encrypted) {
        if (encrypted) {
            flags |= FLAG_ENCRYPTED;
        } else {
            flags &= ~FLAG_ENCRYPTED;
        }
    }

    // ==================== 消息模式方法 ====================

    /**
     * 获取消息模式
     * @return MODE_REQUEST, MODE_RESPONSE, 或 MODE_PUSH
     */
    public short getMode() {
        return (short) (flags & FLAG_MASK_MODE);
    }

    /**
     * 设置消息模式
     * @param mode 消息模式
     */
    public void setMode(short mode) {
        // 先清除原有模式位，再设置新模式
        // ~FLAG_MASK_MODE 取反后，只有模式位为0，其他位为1
        flags = (short) ((flags & ~FLAG_MASK_MODE) | (mode & FLAG_MASK_MODE));
    }

    // ==================== Getter/Setter ====================

    public short getFlags() { return flags; }
    public void setFlags(short flags) { this.flags = flags; }

    public short getSequence() { return sequence; }
    public void setSequence(short sequence) { this.sequence = sequence; }

    public short getMessageId() { return messageId; }
    public void setMessageId(short messageId) { this.messageId = messageId; }

    public int getBodyLength() { return bodyLength; }
    public void setBodyLength(int bodyLength) { this.bodyLength = bodyLength; }

    public int getRequestId() { return requestId; }
    public void setRequestId(int requestId) { this.requestId = requestId; }

    /** 输出 flags、序号、模式等可读摘要，供日志使用。 */
    @Override
    public String toString() {
        String modeStr = switch (getMode()) {
            case MODE_REQUEST -> "REQUEST";
            case MODE_RESPONSE -> "RESPONSE";
            case MODE_PUSH -> "PUSH";
            default -> "UNKNOWN";
        };
        return String.format("MessageHeader[flags=0x%04X, seq=%d, msgId=%d, bodyLen=%d, reqId=%d, mode=%s]",
                flags & 0xFFFF, sequence & 0xFFFF, messageId & 0xFFFF, bodyLength, requestId, modeStr);
    }
}
