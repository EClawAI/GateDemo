package com.clawai.gatedemo.gate.protocol.model;

/**
 * 包装消息 - 游戏协议的完整消息结构
 *
 * 设计原理：
 * 消息分为消息头和消息体两部分，这是网络通信的常见模式。
 * WrappedMessage将这两部分组合成一个完整的消息对象。
 *
 * 消息结构：
 * ┌─────────────────────────────────────────┐
 * │              WrappedMessage              │
 * ├──────────────────┬──────────────────────┤
 * │   MessageHeader │     MessageBody       │
 * │   (14字节)      │     (可变长度)        │
 * ├──────────────────┴──────────────────────┤
 * │  flags (2)   │ sequence (2)            │
 * │  messageId (2)│ bodyLength (4)        │
 * │  requestId (4)│                        │
 * ├─────────────────┴───────────────────────┤
 * │        body (protobuf 二进制)           │
 * └─────────────────────────────────────────┘
 *
 * 使用场景：
 * 1. 编码前：构建要发送的消息
 * 2. 解码后：解析接收到的消息
 * 3. 业务处理：在Handler中传递消息
 *
 * 为什么需要包装类？
 * - 统一消息格式，无论发送还是接收都使用相同结构
 * - 方便在Pipeline中传递数据
 * - Header和Body分离，便于分别处理
 *
 * 与Encoder/Decoder的关系：
 * - Encoder: WrappedMessage → 二进制数据
 * - Decoder: 二进制数据 → WrappedMessage
 */
public class WrappedMessage {

    /**
     * 消息头
     * 包含元数据：标志位、序列号、消息ID、长度、请求ID
     * 固定14字节
     */
    private MessageHeader header;

    /** 消息体：可变长度，protobuf 二进制负载。 */
    private MessageBody body;

    /** 预置空头与空 body，便于出站前逐字段填充。 */
    public WrappedMessage() {
        this.header = new MessageHeader();
        this.body = new RawMessageBody();
    }

    public WrappedMessage(MessageHeader header, MessageBody body) {
        this.header = header;
        this.body = body;
    }

    /** 仅指定消息类型 ID 时常用，头其余字段由后续逻辑补全。 */
    public WrappedMessage(int messageId, MessageBody body) {
        this.header = new MessageHeader(messageId);
        this.body = body;
    }

    public MessageHeader getHeader() {
        return header;
    }

    public void setHeader(MessageHeader header) {
        this.header = header;
    }

    public MessageBody getBody() {
        return body;
    }

    public void setBody(MessageBody body) {
        this.body = body;
    }

    @Override
    public String toString() {
        return "WrappedMessage{header=" + header +
                ", bodyLen=" + (body != null ? body.toBytes().length : 0) + '}';
    }
}
