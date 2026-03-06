package com.clawai.gatedemo.client.protocol.codec;

import com.clawai.gatedemo.client.protocol.model.MessageHeader;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.zip.Deflater;

public class ClientMessageEncoder extends MessageToByteEncoder<WrappedMessage> {

    private static final Logger logger = LoggerFactory.getLogger(ClientMessageEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext ctx, WrappedMessage msg, ByteBuf out) throws Exception {
        if (msg == null || msg.getHeader() == null) {
            return;
        }

        MessageHeader header = msg.getHeader();
        byte[] bodyBytes = msg.getBody() != null ? msg.getBody().toBytes() : new byte[0];

        boolean needCompress = header.isCompressed() && bodyBytes.length > 64;
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

        out.writeShort(header.getFlags());
        out.writeShort(header.getSequence());
        out.writeShort(header.getMessageId());
        out.writeInt(header.getBodyLength());
        out.writeInt(header.getRequestId());

        if (finalBodyBytes.length > 0) {
            out.writeBytes(finalBodyBytes);
        }

        logger.debug("Encoded message: msgId={}, bodyLength={}, compressed={}",
                header.getMessageId(), header.getBodyLength(), needCompress);
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
