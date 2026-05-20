package com.clawai.gatedemo.client.ws.codec;

import com.clawai.gatedemo.client.protocol.model.MessageHeader;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
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
 * 客户端侧 WebSocket 二进制帧编码器：WrappedMessage → BinaryWebSocketFrame。
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

        boolean needCompress = header.isCompressed() && bodyBytes.length > COMPRESS_THRESHOLD;
        byte[] finalBodyBytes = bodyBytes;

        if (needCompress) {
            finalBodyBytes = compress(bodyBytes);
            if (finalBodyBytes == null || finalBodyBytes.length >= bodyBytes.length) {
                finalBodyBytes = bodyBytes;
                needCompress = false;
            }
        }

        header.setBodyLength(finalBodyBytes.length);
        header.setCompressed(needCompress);
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
