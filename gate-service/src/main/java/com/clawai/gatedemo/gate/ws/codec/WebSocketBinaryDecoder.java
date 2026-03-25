package com.clawai.gatedemo.gate.ws.codec;

import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.zip.Inflater;

/**
 * WebSocket 二进制帧解码器：将 {@link BinaryWebSocketFrame} 解析为 {@link WrappedMessage}。
 * <p>
 * 与 TCP 的 {@link com.clawai.gatedemo.gate.protocol.codec.GameMessageDecoder} 共享
 * 同一 14 字节头协议，但无需处理粘包/半包（WebSocket 保证每帧完整）。
 */
@ChannelHandler.Sharable
public class WebSocketBinaryDecoder extends MessageToMessageDecoder<BinaryWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketBinaryDecoder.class);

    private static final int HEADER_SIZE = 16;
    private static final int MAX_BODY_LENGTH = 10 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, BinaryWebSocketFrame frame, List<Object> out) throws Exception {
        ByteBuf in = frame.content();

        if (in.readableBytes() < HEADER_SIZE) {
            logger.error("WebSocket 帧过短: {} bytes, 需要至少 {} bytes", in.readableBytes(), HEADER_SIZE);
            ctx.close();
            return;
        }

        short flags = in.readShort();
        short sequence = in.readShort();
        int messageId = in.readInt();
        int bodyLength = in.readInt();
        int requestId = in.readInt();

        if (bodyLength < 0 || bodyLength > MAX_BODY_LENGTH) {
            logger.error("非法 bodyLength: {}, 关闭连接", bodyLength);
            ctx.close();
            return;
        }

        if (in.readableBytes() < bodyLength) {
            logger.error("帧数据不完整: 期望 {} bytes body, 实际 {}", bodyLength, in.readableBytes());
            ctx.close();
            return;
        }

        MessageHeader header = new MessageHeader();
        header.setFlags(flags);
        header.setSequence(sequence);
        header.setMessageId(messageId);
        header.setBodyLength(bodyLength);
        header.setRequestId(requestId);

        byte[] bodyBytes = new byte[0];
        if (bodyLength > 0) {
            bodyBytes = new byte[bodyLength];
            in.readBytes(bodyBytes);

            if (header.isCompressed()) {
                bodyBytes = decompress(bodyBytes);
                if (bodyBytes == null) {
                    logger.error("解压失败: messageId={}", messageId);
                    return;
                }
            }
        }

        WrappedMessage message = new WrappedMessage(header, new RawMessageBody(bodyBytes));
        logger.debug("WS 解码: msgId={}, bodyLen={}", messageId, bodyLength);
        out.add(message);
    }

    private byte[] decompress(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] decompressed = new byte[data.length * 4];
        try {
            int resultLength = inflater.inflate(decompressed);
            inflater.end();
            if (resultLength > 0) {
                byte[] result = new byte[resultLength];
                System.arraycopy(decompressed, 0, result, 0, resultLength);
                return result;
            }
        } catch (Exception e) {
            logger.error("解压异常: {}", e.getMessage());
            inflater.end();
        }
        return null;
    }
}
