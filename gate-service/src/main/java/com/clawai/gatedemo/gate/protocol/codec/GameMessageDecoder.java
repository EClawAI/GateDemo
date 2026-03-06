package com.clawai.gatedemo.gate.protocol.codec;

import com.clawai.gatedemo.gate.protocol.model.JsonMessageBody;
import com.clawai.gatedemo.gate.protocol.model.MessageBody;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.zip.Inflater;

public class GameMessageDecoder extends ByteToMessageDecoder {

    private static final Logger logger = LoggerFactory.getLogger(GameMessageDecoder.class);

    private static final int HEADER_SIZE = 14;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < HEADER_SIZE) {
            return;
        }

        in.markReaderIndex();

        short flags = in.readShort();
        short sequence = in.readShort();
        short messageId = in.readShort();
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

        MessageBody body = new JsonMessageBody();
        body.fromBytes(bodyBytes);

        WrappedMessage message = new WrappedMessage(header, body);

        logger.debug("Decoded message: msgId={}, bodyLength={}, compressed={}",
                messageId, bodyLength, header.isCompressed());

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
            logger.error("Decompress error: {}", e.getMessage());
            inflater.end();
        }
        return null;
    }
}
