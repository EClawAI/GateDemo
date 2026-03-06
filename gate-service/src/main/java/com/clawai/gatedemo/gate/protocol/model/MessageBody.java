package com.clawai.gatedemo.gate.protocol.model;

import java.util.Map;

/**
 * 消息体接口 - 定义消息内容的序列化/反序列化规范
 *
 * 设计原理：
 * 消息体与消息头分离，消息头固定14字节，消息体为可变长度。
 * 使用接口定义规范，便于扩展不同序列化方式（JSON、Protobuf等）。
 *
 * 接口方法说明：
 * 1. 二进制序列化：toBytes() / fromBytes()
 *    - 用于网络传输，二进制格式效率高
 *    - 内部使用Jackson进行JSON序列化，再转字节数组
 *
 * 2. JSON序列化：toJson() / fromJson()
 *    - 用于调试、日志、HTTP等文本场景
 *    - 方便人类阅读和调试
 *
 * 3. 数据访问：getData() / setData()
 *    - 提供对消息内容的Map形式访问
 *    - 便于业务逻辑处理
 *
 * 实现类选择：
 * - JsonMessageBody: 使用JSON格式，兼容性好，易于调试
 * - (可选) ProtobufMessageBody: 使用Protobuf，性能更高，需要IDL定义
 *
 * @see JsonMessageBody 默认实现
 */
public interface MessageBody {

    /**
     * 将消息体序列化为字节数组
     * 用于网络传输
     * @return 字节数组
     */
    byte[] toBytes();

    /**
     * 从字节数组反序列化消息体
     * @param bytes 字节数组
     */
    void fromBytes(byte[] bytes);

    /**
     * 将消息体序列化为JSON字符串
     * 用于调试、日志
     * @return JSON字符串
     */
    String toJson();

    /**
     * 从JSON字符串反序列化消息体
     * @param json JSON字符串
     */
    void fromJson(String json);

    /**
     * 获取消息数据
     * @return 消息数据的Map形式
     */
    Map<String, Object> getData();

    /**
     * 设置消息数据
     * @param data 消息数据
     */
    void setData(Map<String, Object> data);
}
