package com.clawai.gatedemo.gate.protocol.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON格式消息体实现 - MessageBody接口的默认实现
 *
 * 设计原理：
 * 使用Jackson库进行JSON与Map之间的相互转换。
 * Map<String, Object>是最灵活的数据结构，可以存储任意类型的数据。
 *
 * 技术选型：
 * - Jackson: Spring生态标配，功能强大，支持注解定制
 * - Map<String, Object>: 动态结构，无需预定义POJO，适合游戏业务的灵活数据结构
 *
 * 性能优化：
 * - ObjectMapper静态实例：避免重复创建，减少内存开销
 * - 空值保护：所有方法都有null检查，返回安全的默认值
 *
 * 序列化流程：
 * 1. 业务层构建Map<String, Object>数据
 * 2. 调用toBytes()转JSON字节数组用于网络传输
 * 3. 接收方调用fromBytes()还原Map数据
 * 4. 业务层从Map中获取数据
 *
 * 使用示例：
 * <pre>
 * // 构建消息
 * Map<String, Object> data = new HashMap<>();
 * data.put("playerId", 12345);
 * data.put("action", "move");
 * data.put("x", 100);
 * data.put("y", 200);
 *
 * JsonMessageBody body = new JsonMessageBody(data);
 * byte[] bytes = body.toBytes();  // 网络传输
 *
 * // 解析消息
 * JsonMessageBody body = new JsonMessageBody();
 * body.fromBytes(bytes);
 * Map<String, Object> data = body.getData();
 * </pre>
 */
public class JsonMessageBody implements MessageBody {

    /**
     * Jackson ObjectMapper 静态实例
     * 用于JSON与Map之间的相互转换
     * 为什么不使用实例变量？
     * - ObjectMapper是线程安全的，可以共享使用
     * - 静态实例避免重复创建
     */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 消息数据存储
     * 使用Map结构存储键值对，灵活度高
     * 业务数据直接映射为Map的key-value
     */
    private Map<String, Object> data;

    /** 用于解码侧构造空体。 */
    public JsonMessageBody() {
    }

    public JsonMessageBody(Map<String, Object> data) {
        this.data = data;
    }

    /**
     * 将 {@link #data} 序列化为 JSON UTF-8；{@code data==null} 或序列化异常时返回空数组（调用方需容忍）。
     *
     * @return 字节数组，可能长度为 0
     */
    @Override
    public byte[] toBytes() {
        if (data == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (JsonProcessingException e) {
            return new byte[0];
        }
    }

    /**
     * 自 JSON 字节解析为 Map；空数组将 {@link #data} 置 null，解析失败同样置 null。
     */
    @Override
    public void fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            this.data = null;
            return;
        }
        try {
            this.data = objectMapper.readValue(bytes, Map.class);
        } catch (Exception e) {
            this.data = null;
        }
    }

    /**
     * 序列化为JSON字符串（用于调试/日志）
     *
     * 与toBytes()的区别：
     * - toBytes(): 返回字节数组，用于网络传输
     * - toJson(): 返回字符串，用于人类阅读
     *
     * @return JSON字符串
     */
    @Override
    public String toJson() {
        if (data == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * 从JSON字符串反序列化
     * @param json JSON字符串
     */
    @Override
    public void fromJson(String json) {
        if (json == null || json.isEmpty()) {
            this.data = null;
            return;
        }
        try {
            this.data = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            this.data = null;
        }
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
