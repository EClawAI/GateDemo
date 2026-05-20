package com.clawai.gatedemo.client.ws.codec;

import com.clawai.gatedemo.client.protocol.model.MessageHeader;
import com.clawai.gatedemo.client.protocol.model.RawMessageBody;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
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
 * 客户端侧 WebSocket 二进制帧解码器：BinaryWebSocketFrame → WrappedMessage。
 */
@ChannelHandler.Sharable
public class WebSocketBinaryDecoder extends MessageToMessageDecoder<BinaryWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketBinaryDecoder.class);
    private static final int HEADER_SIZE = 16;

    @Override
    protected void decode(ChannelHandlerContext ctx, BinaryWebSocketFrame frame, List<Object> out) throws Exception {
        ByteBuf in = frame.content();

        if (in.readableBytes() < HEADER_SIZE) {
            logger.error("帧过短: {} bytes", in.readableBytes());
            return;
        }

        short flags = in.readShort();
        short sequence = in.readShort();
        int messageId = in.readInt();
        int bodyLength = in.readInt();
        int requestId = in.readInt();

        if (bodyLength < 0 || bodyLength > 10 * 1024 * 1024) {
            logger.error("非法 bodyLength: {}", bodyLength);
            ctx.close();
            return;
        }

        boolean hasGwSeq = (flags & MessageHeader.FLAG_HAS_GW_SEQ) != 0;
        if (hasGwSeq && in.readableBytes() < 8) {
            logger.error("FLAG_HAS_GW_SEQ 置位但帧长不足 gwSeq 字段");
            ctx.close();
            return;
        }
        long gwSeq = hasGwSeq ? in.readLong() : 0L;

        MessageHeader header = new MessageHeader();
        header.setFlags(flags);
        header.setSequence(sequence);
        header.setMessageId(messageId);
        header.setBodyLength(bodyLength);
        header.setRequestId(requestId);
        header.setGwSeq(gwSeq);

        byte[] bodyBytes = new byte[0];
        if (bodyLength > 0 && in.readableBytes() >= bodyLength) {
            bodyBytes = new byte[bodyLength];
            in.readBytes(bodyBytes);

            if (header.isCompressed()) {
                bodyBytes = decompress(bodyBytes);
                if (bodyBytes == null) {
                    logger.error("解压失败: messageId=0x{}", Integer.toHexString(messageId & 0xFFFF));
                    return;
                }
            }
        }

        WrappedMessage message = new WrappedMessage(header, new RawMessageBody(bodyBytes));
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
