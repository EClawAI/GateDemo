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
 * │           body (JSON二进制)             │
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

    /**
     * 消息体
     * 包含实际业务数据
     * 可变长度，使用JSON序列化
     */
    private MessageBody body;

    /**
     * 默认构造函数
     * 创建一个空消息，常用于接收数据
     * 自动创建默认的Header和Body
     */
    public WrappedMessage() {
        this.header = new MessageHeader();
        this.body = new JsonMessageBody();
    }

    /**
     * 完全构造函数
     * @param header 消息头
     * @param body 消息体
     */
    public WrappedMessage(MessageHeader header, MessageBody body) {
        this.header = header;
        this.body = body;
    }

    /**
     * 简化的构造函数
     * 只需要指定消息ID和消息体时使用
     * @param messageId 消息ID
     * @param body 消息体
     */
    public WrappedMessage(short messageId, MessageBody body) {
        this.header = new MessageHeader(messageId);
        this.body = body;
    }

    /**
     * 获取消息头
     * @return 消息头引用
     */
    public MessageHeader getHeader() {
        return header;
    }

    /**
     * 设置消息头
     * @param header 消息头
     */
    public void setHeader(MessageHeader header) {
        this.header = header;
    }

    /**
     * 获取消息体
     * @return 消息体引用
     */
    public MessageBody getBody() {
        return body;
    }

    /**
     * 设置消息体
     * @param body 消息体
     */
    public void setBody(MessageBody body) {
        this.body = body;
    }

    /**
     * 转换为字符串表示
     * 用于日志和调试
     * @return 字符串形式的消息
     */
    @Override
    public String toString() {
        return "WrappedMessage{" +
                "header=" + header +
                ", body=" + (body != null ? body.toJson() : "null") +
                '}';
    }
}
