package com.clawai.gatedemo.common.route;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息路由注册表：维护 messageId ↔ 消息名 ↔ 目标服务 的映射关系。
 * 由 {@link MessageRouteScanner} 启动时自动扫描 proto descriptor 填充，
 * 也支持手动 {@link #register} 补充。
 */
public final class MessageRouteRegistry {

    /**
     * 路由信息：消息 ID（32 位）、消息名称、目标服务。
     */
    public record RouteInfo(int msgId, String name, String targetService) {}

    private static final Map<Integer, RouteInfo> ID_MAP = new ConcurrentHashMap<>();
    private static final Map<String, RouteInfo> NAME_MAP = new ConcurrentHashMap<>();

    private MessageRouteRegistry() {}

    /** 注册一条路由（重复 msgId 会覆盖）。 */
    public static void register(String name, int msgId, String targetService) {
        RouteInfo info = new RouteInfo(msgId, name, targetService);
        ID_MAP.put(msgId, info);
        NAME_MAP.put(name, info);
    }

    /** 按 messageId 查路由，未注册返回 null。 */
    public static RouteInfo getByMsgId(int msgId) {
        return ID_MAP.get(msgId);
    }

    /** 按消息名查路由，未注册返回 null。 */
    public static RouteInfo getByName(String name) {
        return NAME_MAP.get(name);
    }

    /** 按 messageId 查目标服务，未注册返回 null。 */
    public static String getTargetService(int msgId) {
        RouteInfo info = ID_MAP.get(msgId);
        return info != null ? info.targetService() : null;
    }

    /** 按消息名查 messageId；未注册返回 0。 */
    public static int getIdByName(String name) {
        RouteInfo info = NAME_MAP.get(name);
        return info != null ? info.msgId() : 0;
    }

    /** 按 messageId 查消息名；未注册返回 null。 */
    public static String getNameById(int msgId) {
        RouteInfo info = ID_MAP.get(msgId);
        return info != null ? info.name() : null;
    }

    /** 当前注册条目数。 */
    public static int size() {
        return ID_MAP.size();
    }

    /** 清空注册表（测试用）。 */
    public static void clear() {
        ID_MAP.clear();
        NAME_MAP.clear();
    }
}
