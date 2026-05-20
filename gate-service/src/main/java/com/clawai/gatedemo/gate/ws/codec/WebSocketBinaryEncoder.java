package com.clawai.gatedemo.gate.ws.codec;

import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.zip.Deflater;

/**
 * WebSocket 二进制帧编码器：将 {@link WrappedMessage} 编码为 {@link BinaryWebSocketFrame}。
 * <p>
 * 与 TCP 的 {@link com.clawai.gatedemo.gate.protocol.codec.GameMessageEncoder} 共享
 * 同一 14 字节头协议格式和压缩策略。
 */
@ChannelHandler.Sharable
public class WebSocketBinaryEncoder extends MessageToMessageEncoder<WrappedMessage> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketBinaryEncoder.class);
    private static final int COMPRESS_THRESHOLD = 64;

    @Override
    protected void encode(ChannelHandlerContext ctx, WrappedMessage msg, List<Object> out) throws Exception {
        if (msg == null || msg.getHeader() == null) {
            return;
        }

        MessageHeader header = msg.getHeader();
        byte[] bodyBytes = msg.getBody() != null ? msg.getBody().toBytes() : new byte[0];

        boolean compressed = false;
        byte[] finalBodyBytes = bodyBytes;

        if (bodyBytes.length > COMPRESS_THRESHOLD) {
            byte[] result = compress(bodyBytes);
            if (result != null && result.length < bodyBytes.length) {
                finalBodyBytes = result;
                compressed = true;
            }
        }

        header.setBodyLength(finalBodyBytes.length);
        header.setCompressed(compressed);
        boolean withGwSeq = header.hasGwSeq();

        int totalSize = 16 + (withGwSeq ? 8 : 0) + finalBodyBytes.length;
        ByteBuf buf = ctx.alloc().buffer(totalSize);
        buf.writeShort(header.getFlags());
        buf.writeShort(header.getSequence());
        buf.writeInt(header.getMessageId());
        buf.writeInt(header.getBodyLength());
        buf.writeInt(header.getRequestId());
        if (withGwSeq) {
            buf.writeLong(header.getGwSeq());
        }

        if (finalBodyBytes.length > 0) {
            buf.writeBytes(finalBodyBytes);
        }

        out.add(new BinaryWebSocketFrame(buf));
        logger.debug("WS 编码: msgId={}, bodyLen={}, compressed={}, gwSeq={}",
                header.getMessageId(), header.getBodyLength(), compressed,
                withGwSeq ? header.getGwSeq() : "-");
    }

    private byte[] compress(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] compressed = new byte[data.length];
        int compressedLength = deflater.deflate(compressed);
        deflater.end();
        if (compressedLength < data.length) {
            byte[] result = new byte[compressedLength];
            System.arraycopy(compressed, 0, result, 0, compressedLength);
            return result;
        }
        return null;
    }
}
