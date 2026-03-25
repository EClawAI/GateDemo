package com.clawai.gatedemo.client.protocol.codec;

import com.clawai.gatedemo.client.protocol.model.MessageHeader;
import com.clawai.gatedemo.client.protocol.model.RawMessageBody;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.zip.Inflater;

/**
 * Netty 侧二进制帧解码，与网关解码规则对齐：定长头 + 可选解压 + 原始二进制体（protobuf bytes）。
 */
public class ClientMessageDecoder extends ByteToMessageDecoder {

    private static final Logger logger = LoggerFactory.getLogger(ClientMessageDecoder.class);

    private static final int HEADER_SIZE = 16;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < HEADER_SIZE) {
            return;
        }

        in.markReaderIndex();

        short flags = in.readShort();
        short sequence = in.readShort();
        int messageId = in.readInt();
        int bodyLength = in.readInt();
        int requestId = in.readInt();

        if (bodyLength < 0 || bodyLength > 10 * 1024 * 1024) {
            logger.error("Invalid body length: {}", bodyLength);
            ctx.close();
            return;
        }

        if (in.readableBytes() < bodyLength) {
            in.resetReaderIndex();
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
                    logger.error("Decompress failed for messageId={}", messageId);
                    return;
                }
            }
        }

        RawMessageBody body = new RawMessageBody(bodyBytes);

        WrappedMessage message = new WrappedMessage(header, body);

        logger.debug("Decoded message: msgId={}, bodyLength={}, compressed={}",
                messageId, bodyLength, header.isCompressed());

        out.add(message);
    }

    /**
     * zlib 解压载荷；失败或空结果返回 {@code null}，由调用方记录并中止本帧处理。
     *
     * @param data 压缩后的体字节
     * @return 解压后的 JSON 体字节，失败为 null
     */
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
            logger.error("Decompress error: {}", e.getMessage());
            inflater.end();
        }
        return null;
    }
}
