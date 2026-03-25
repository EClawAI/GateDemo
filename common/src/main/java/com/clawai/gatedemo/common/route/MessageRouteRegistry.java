package com.clawai.gatedemo.common.route;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息路由注册表：维护 messageId ↔ 消息名 ↔ 目标服务 的映射关系。
 * 启动时通过 {@link #loadFromJson(String)} 从 classpath 中的 JSON 文件加载，
 * 也支持手动 {@link #register} 补充。
 */
public final class MessageRouteRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MessageRouteRegistry.class);

    /**
     * 路由信息：消息 ID（32 位）、消息名称、目标服务。
     */
    public record RouteInfo(int msgId, String name, String targetService) {}

    private static final Map<Integer, RouteInfo> ID_MAP = new ConcurrentHashMap<>();
    private static final Map<String, RouteInfo> NAME_MAP = new ConcurrentHashMap<>();

    private MessageRouteRegistry() {}

    /**
     * 从 classpath 资源加载 message_registry.json 并填充路由映射。
     *
     * @param resourcePath classpath 上的资源路径（如 "message_registry.json"）
     * @throws IllegalStateException JSON 文件不存在或格式错误
     */
    public static void loadFromJson(String resourcePath) {
        InputStream is = MessageRouteRegistry.class.getClassLoader()
                .getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalStateException(
                    "消息注册表文件未找到: " + resourcePath
                    + "，请先运行 tools/gen_proto.sh 生成");
        }

        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray messages = root.getAsJsonArray("messages");
            if (messages == null) {
                throw new IllegalStateException(
                        "message_registry.json 格式错误: 缺少 'messages' 数组");
            }

            clear();
            for (JsonElement elem : messages) {
                JsonObject msg = elem.getAsJsonObject();
                int id = (int) msg.get("id").getAsLong();
                String name = msg.get("name").getAsString();
                String service = msg.has("service") && !msg.get("service").isJsonNull()
                        ? msg.get("service").getAsString() : null;
                register(name, id, service);
            }
            logger.info("从 {} 加载消息路由表完成，共 {} 条", resourcePath, size());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "加载消息注册表失败: " + resourcePath, e);
        }
    }

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
