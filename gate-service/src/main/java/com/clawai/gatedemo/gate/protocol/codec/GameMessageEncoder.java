package com.clawai.gatedemo.gate.protocol.codec;

import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.zip.Deflater;

/**
 * 游戏消息编码器 - 将Java对象转换为二进制数据用于网络传输
 *
 * Netty职责：
 * - 继承MessageToByteEncoder，自动拦截write操作
 * - 当上层写入WrappedMessage时，自动调用encode方法
 * - 将编码后的字节写入Channel
 *
 * 编码流程：
 * 1. 提取消息头和消息体
 * 2. 判断是否需要压缩（body > 64字节）
 * 3. 压缩消息体（可选）
 * 4. 写入消息头（14字节）
 * 5. 写入消息体
 *
 * 二进制协议格式：
 * |  2字节  |  2字节  |  2字节  |   4字节   |   4字节   |   N字节    |
 * |  flags  | sequence | messageId | bodyLength | requestId | bodyBytes  |
 *
 * 压缩策略：
 * - 仅当消息体大于64字节时才压缩
 * - 压缩后如果体积没有减小，则放弃压缩
 * - 使用Java内置的Deflater（DEFLATE算法）
 *
 * 设计考量：
 * - 为什么不自动压缩所有消息？
 *   压缩有CPU开销，小消息压缩后可能反而更大
 * - 为什么选择DEFLATE而非gzip？
 *   DEFLATE是gzip的核心算法，更轻量
 * - ByteBuf直接写入的优点？
 *   减少中间字节数组创建，Netty推荐写法
 */
public class GameMessageEncoder extends MessageToByteEncoder<WrappedMessage> {

    /** 日志记录器，用于调试和问题排查 */
    private static final Logger logger = LoggerFactory.getLogger(GameMessageEncoder.class);

    /** 压缩阈值：大于此字节数才进行压缩 */
    private static final int COMPRESS_THRESHOLD = 64;

    /**
     * 将 {@link WrappedMessage} 写成头+体；可能按阈值压缩并回写 {@link MessageHeader#setBodyLength(int)} 与压缩标志。
     *
     * @param ctx Netty 上下文
     * @param msg null 或 header 为 null 时直接返回，不写 out
     * @param out 编码输出缓冲
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, WrappedMessage msg, ByteBuf out) throws Exception {
        // 参数校验：消息或消息头为空则跳过
        if (msg == null || msg.getHeader() == null) {
            return;
        }

        MessageHeader header = msg.getHeader();
        byte[] bodyBytes = msg.getBody() != null ? msg.getBody().toBytes() : new byte[0];

        // 编码器自主决定压缩：body 超过阈值则尝试，压缩后更小才采用
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

        // 步骤5: 写入消息头（固定14字节）
        // 写入顺序必须与解码器一致
        out.writeShort(header.getFlags());      // 2字节：标志位
        out.writeShort(header.getSequence());   // 2字节：序列号
        out.writeInt(header.getMessageId());    // 4字节：消息ID
        out.writeInt(header.getBodyLength());   // 4字节：消息体长度
        out.writeInt(header.getRequestId());    // 4字节：请求ID

        // 步骤6: 写入消息体
        if (finalBodyBytes.length > 0) {
            out.writeBytes(finalBodyBytes);
        }

        // 调试日志：记录编码结果
        logger.debug("Encoded message: msgId={}, bodyLength={}, compressed={}",
                header.getMessageId(), header.getBodyLength(), compressed);
    }

    /**
     * 使用 {@link Deflater} 做 DEFLATE；仅当压缩后严格变短才返回新数组，否则返回 null 表示沿用原文。
     *
     * @param data 原始消息体字节
     * @return 更短的压缩结果，或 null
     */
    private byte[] compress(byte[] data) {
        // 创建DEFLATE压缩器
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();

        // 预分配输出缓冲区（最大为原始大小）
        byte[] compressed = new byte[data.length];
        // 执行压缩，返回实际压缩后的大小
        int compressedLength = deflater.deflate(compressed);
        // 释放压缩器资源
        deflater.end();

        // 如果压缩成功且体积减小
        if (compressedLength < data.length) {
            // 创建正确大小的数组返回
            byte[] result = new byte[compressedLength];
            System.arraycopy(compressed, 0, result, 0, compressedLength);
            return result;
        }

        // 压缩失败或未压缩
        return null;
    }
}
