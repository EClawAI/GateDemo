package com.clawai.gatedemo.client.protocol.codec;

import com.clawai.gatedemo.client.protocol.model.MessageHeader;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.zip.Deflater;

/**
 * 将 {@link WrappedMessage} 编码为与网关一致的定长头 + 载荷字节流，按需压缩，保证客户端发帧与 gate 解析一致。
 */
public class ClientMessageEncoder extends MessageToByteEncoder<WrappedMessage> {

    private static final Logger logger = LoggerFactory.getLogger(ClientMessageEncoder.class);

    /**
     * 将头与体写入定长头 + 变长体；体在标志允许且足够大时尝试压缩，并回写 {@link MessageHeader} 的长度与压缩位。
     *
     * @param ctx Netty 上下文
     * @param msg 逻辑报文
     * @param out 输出缓冲
     */
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

    /**
     * zlib 压缩；若压缩后不小于原数据则返回 {@code null}，调用方回退明文。
     *
     * @param data 原始体字节
     * @return 更短的压缩字节，或 null 表示不值得压缩
     */
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
