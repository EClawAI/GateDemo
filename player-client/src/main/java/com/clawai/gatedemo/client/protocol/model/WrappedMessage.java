package com.clawai.gatedemo.client.protocol.model;

/**
 * 编解码管道中的逻辑报文单元：头与体组合，串联
 * {@link com.clawai.gatedemo.client.protocol.codec.ClientMessageEncoder}/
 * {@link com.clawai.gatedemo.client.protocol.codec.ClientMessageDecoder} 与上层监听。
 */
public class WrappedMessage {

    private MessageHeader header;
    private MessageBody body;

    /** 构造空头与空 JSON 体，便于出站前逐字段填充 */
    public WrappedMessage() {
        this.header = new MessageHeader();
        this.body = new JsonMessageBody();
    }

    /**
     * @param header 已配置的头（含 messageId、requestId 等）
     * @param body   与头配套的载荷实现
     */
    public WrappedMessage(MessageHeader header, MessageBody body) {
        this.header = header;
        this.body = body;
    }

    /**
     * @param messageId 业务消息 ID
     * @param body      载荷
     */
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

    /**
     * 便于日志输出；体通过 {@link MessageBody#toJson()} 展开。
     */
    @Override
    public String toString() {
        return "WrappedMessage{" +
                "header=" + header +
                ", body=" + (body != null ? body.toJson() : "null") +
                '}';
    }
}
