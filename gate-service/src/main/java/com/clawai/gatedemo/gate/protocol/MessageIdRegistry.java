package com.clawai.gatedemo.gate.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息ID注册表 - 管理消息名称与ID的映射关系
 *
 * 设计原理：
 * 虽然MessageIdGenerator可以自动生成ID，但有以下原因需要注册表：
 * 1. 手动指定关键消息的ID，便于调试和文档记录
 * 2. 可以覆盖自动生成的ID（解决哈希冲突）
 * 3. 提供ID到名称的反向查询，用于日志和监控
 *
 * 注册机制：
 * - 静态代码块中注册常用消息
 * - 支持运行时动态注册
 * - 未注册的消息使用自动生成
 *
 * ID分配规则（建议）：
 * - 0x1000-0x1FFF: 认证模块（auth.*）
 * - 0x2000-0x2FFF: 心跳模块（heartbeat.*）
 * - 0x3000-0x3FFF: 战斗模块（battle.*）
 * - 0x4000-0x4FFF: 聊天模块（chat.*）
 * - 0x5000-0x5FFF: 背包模块（bag.*）
 * - 以此类推...
 *
 * 使用示例：
 * <pre>
 * short loginId = MessageIdRegistry.getIdByName("auth.login");  // 返回0x1001
 * String name = MessageIdRegistry.getNameById((short) 0x1001);  // 返回"auth.login"
 * </pre>
 *
 * @see MessageIdGenerator 自动生成ID
 */
public class MessageIdRegistry {

    /** ID → 名称 的映射 */
    private static final Map<Short, String> ID_TO_NAME = new HashMap<>();

    /** 名称 → ID 的映射 */
    private static final Map<String, Short> NAME_TO_ID = new HashMap<>();

    /**
     * 静态初始化块
     * 在类加载时注册常用的消息ID
     *
     * 注册规则：
     * - 认证模块：0x1000-0x1FFF
     * - 心跳模块：0x2000-0x2FFF
     * - 战斗模块：0x3000-0x3FFF
     * - 聊天模块：0x4000-0x4FFF
     */
    static {
        // 认证模块
        register("auth.login", (short) 0x1001);    // 登录
        register("auth.logout", (short) 0x1002);   // 登出
        register("auth.relogin", (short) 0x1003);  // 顶号（离线消息过多时）

        // 心跳模块
        register("heartbeat", (short) 0x2001);        // 心跳
        register("heartbeat_ack", (short) 0x2002);    // 心跳响应

        // 战斗模块
        register("battle.move", (short) 0x3001);      // 移动
        register("battle.attack", (short) 0x3002);    // 攻击

        // 聊天模块
        register("chat.send", (short) 0x4001);        // 发送聊天
        register("chat.receive", (short) 0x4002);    // 接收聊天
    }

    /**
     * 注册消息ID
     *
     * 双向注册：
     * - NAME_TO_ID: 名称 → ID
     * - ID_TO_NAME: ID → 名称
     *
     * @param name 消息名称
     * @param id 消息ID
     */
    private static void register(String name, short id) {
        NAME_TO_ID.put(name, id);
        ID_TO_NAME.put(id, name);
    }

    /**
     * 根据名称获取ID
     *
     * 查找顺序：
     * 1. 先在注册表中查找
     * 2. 未找到则使用MessageIdGenerator自动生成
     *
     * @param name 消息名称
     * @return 消息ID
     */
    public static short getIdByName(String name) {
        Short id = NAME_TO_ID.get(name);
        // 如果已注册则返回注册ID，否则自动生成
        return id != null ? id : MessageIdGenerator.generateId(name);
    }

    /**
     * 根据ID获取名称
     *
     * 用于日志、监控等场景
     *
     * @param id 消息ID
     * @return 消息名称，未注册返回null
     */
    public static String getNameById(short id) {
        return ID_TO_NAME.get(id);
    }

}
