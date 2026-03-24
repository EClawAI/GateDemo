package com.clawai.gatedemo.client.protocol.model;

/**
 * 编解码管道中的逻辑报文单元：头与体组合，串联
 * {@link com.clawai.gatedemo.client.protocol.codec.ClientMessageEncoder}/
 * {@link com.clawai.gatedemo.client.protocol.codec.ClientMessageDecoder} 与上层监听。
 */
public class WrappedMessage {

    private MessageHeader header;
    private MessageBody body;

    public WrappedMessage() {
        this.header = new MessageHeader();
        this.body = new JsonMessageBody();
    }

    public WrappedMessage(MessageHeader header, MessageBody body) {
        this.header = header;
        this.body = body;
    }

    public WrappedMessage(short messageId, MessageBody body) {
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
        return "WrappedMessage{" +
                "header=" + header +
                ", body=" + (body != null ? body.toJson() : "null") +
                '}';
    }
}
