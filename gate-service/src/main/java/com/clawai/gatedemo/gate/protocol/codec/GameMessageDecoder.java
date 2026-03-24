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

/**
 * 游戏消息解码器 - 将二进制数据转换为Java对象
 *
 * Netty职责：
 * - 继承ByteToMessageDecoder，自动拦截read操作
 * - 当从Channel读取到数据时，自动调用decode方法
 * - 将解析后的Java对象传递给下一个Handler
 *
 * 解码流程：
 * 1. 检查是否读到完整的消息头（14字节）
 * 2. 读取消息头字段
 * 3. 验证消息体长度（防攻击）
 * 4. 检查是否读到完整的消息体
 * 5. 解压消息体（如果需要）
 * 6. 反序列化为MessageBody
 * 7. 组装成WrappedMessage
 *
 * 粘包/半包处理：
 * - 使用markReaderIndex/resetReaderIndex处理半包
 * - 只有数据完整时才向下传递
 *
 * 安全性检查：
 * - 消息体长度必须在0-10MB之间
 * - 超过限制直接关闭连接，防止攻击
 *
 * 二进制协议格式（与Encoder对应）：
 * |  2字节  |  2字节  |  2字节  |   4字节   |   4字节   |   N字节    |
 * |  flags  | sequence | messageId | bodyLength | requestId | bodyBytes  |
 *
 * 设计考量：
 * - 为什么不一次读取所有数据？
 *   TCP可能出现粘包/半包，需要按协议边界分割
 * - 为什么用Inflater而不是GZIPInputStream？
 *   DEFLATE更轻量，与Encoder的Deflater对应
 * - 为什么要检查bodyLength范围？
 *   防止恶意构造的大长度导致内存溢出
 */
public class GameMessageDecoder extends ByteToMessageDecoder {

    /** 日志记录器 */
    private static final Logger logger = LoggerFactory.getLogger(GameMessageDecoder.class);

    /** 消息头固定长度：14字节 */
    private static final int HEADER_SIZE = 14;

    /** 消息体最大长度：10MB，防止内存溢出 */
    private static final int MAX_BODY_LENGTH = 10 * 1024 * 1024;

    /**
     * 按 14 字节头 + 定长体拆包；半包则复位读指针待下次；非法 {@code bodyLength} 会关闭连接。
     *
     * @param ctx Netty 上下文
     * @param in  可读字节缓冲
     * @param out 成功时追加 {@link WrappedMessage}；解压失败时可能不产出消息（见实现）
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 步骤1: 检查是否读到完整的消息头
        // 消息头固定14字节，不够14字节说明数据不完整（半包）
        if (in.readableBytes() < HEADER_SIZE) {
            return;  // 数据不完整，等待下次数据到达
        }

        // 步骤2: 标记当前位置，以便后续可能的回退
        // 如果后续发现数据仍不完整，可以resetReaderIndex回退
        in.markReaderIndex();

        // 步骤3: 读取消息头（14字节）
        // 读取顺序必须与Encoder写入顺序完全一致
        short flags = in.readShort();      // 2字节：标志位
        short sequence = in.readShort();   // 2字节：序列号
        short messageId = in.readShort();  // 2字节：消息ID
        int bodyLength = in.readInt();     // 4字节：消息体长度
        int requestId = in.readInt();      // 4字节：请求ID

        // 步骤4: 验证消息体长度（安全检查）
        // 负数或超过最大值，可能是恶意攻击
        if (bodyLength < 0 || bodyLength > MAX_BODY_LENGTH) {
            logger.error("Invalid body length: {}, closing connection", bodyLength);
            ctx.close();  // 关闭连接
            return;
        }

        // 步骤5: 检查是否读到完整的消息体
        // 不够则回退readerIndex，等待下次数据到达
        if (in.readableBytes() < bodyLength) {
            in.resetReaderIndex();  // 回退到标记位置
            return;  // 数据不完整，等待下次数据到达
        }

        // 步骤6: 解析消息头
        MessageHeader header = new MessageHeader();
        header.setFlags(flags);
        header.setSequence(sequence);
        header.setMessageId(messageId);
        header.setBodyLength(bodyLength);
        header.setRequestId(requestId);

        // 步骤7: 读取消息体
        byte[] bodyBytes = new byte[0];
        if (bodyLength > 0) {
            // 创建数组并读取数据
            bodyBytes = new byte[bodyLength];
            in.readBytes(bodyBytes);

            // 步骤8: 解压（如果消息被压缩）
            if (header.isCompressed()) {
                bodyBytes = decompress(bodyBytes);
                if (bodyBytes == null) {
                    logger.error("Decompress failed for messageId={}", messageId);
                    return;  // 解压失败，丢弃该消息
                }
            }
        }

        // 步骤9: 反序列化消息体
        // 使用JsonMessageBody将字节数组转换为Map
        MessageBody body = new JsonMessageBody();
        body.fromBytes(bodyBytes);

        // 步骤10: 组装完整消息
        WrappedMessage message = new WrappedMessage(header, body);

        // 调试日志
        logger.debug("Decoded message: msgId={}, bodyLength={}, compressed={}",
                messageId, bodyLength, header.isCompressed());

        // 步骤11: 将解码后的消息传递给下一个Handler
        out.add(message);
    }

    /**
     * DEFLATE 压缩流的解压；输出缓冲区按输入约 4 倍预分配，异常或空结果返回 null。
     *
     * @param data 编码端 {@link GameMessageEncoder} 写入的压缩体
     * @return 解压后字节；失败返回 null（调用方丢弃该条消息）
     */
    private byte[] decompress(byte[] data) {
        // 创建INFLATE解压器
        Inflater inflater = new Inflater();
        inflater.setInput(data);

        // 预分配输出缓冲区（原始大小的4倍，足够解压）
        byte[] decompressed = new byte[data.length * 4];
        try {
            // 执行解压，返回实际解压后的大小
            int resultLength = inflater.inflate(decompressed);
            // 释放资源
            inflater.end();

            // 如果解压成功
            if (resultLength > 0) {
                // 创建正确大小的数组返回
                byte[] result = new byte[resultLength];
                System.arraycopy(decompressed, 0, result, 0, resultLength);
                return result;
            }
        } catch (Exception e) {
            logger.error("Decompress error: {}", e.getMessage());
            inflater.end();
        }

        // 解压失败
        return null;
    }
}
