# 消息ID生成方案

## 1. 设计目标

- 根据消息名（String）自动生成消息ID
- 生成的ID符合用户的消息结构定义（2字节，无符号，0-65535）
- 手动维护映射表，支持查询和文档生成

## 2. 生成算法

### 2.1 FNV-1a Hash算法

使用FNV-1a 64位算法计算消息名的hash值，然后取低16位作为消息ID：

```java
public class MessageIdGenerator {

    private static final long FNV_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_64_PRIME = 0x100000001b3L;

    public static int generateMessageId(String messageName) {
        long hash = FNV_64_OFFSET_BASIS;

        for (byte b : messageName.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b;
            hash *= FNV_64_PRIME;
        }

        return (int) (hash & 0xFFFF);
    }
}
```

### 2.2 算法特点

- **FNV-1a特点**：
  - 哈希分布均匀
  - 计算速度快
  - 碰撞率低

- **为什么取低16位**：
  - 消息ID定义为2字节，无符号，范围0-65535
  - 低16位已经足够满足消息数量需求

### 2.3 示例

| 消息名 | Hash (64位) | 消息ID (低16位) |
|--------|-------------|-----------------|
| battle.move | 0x8a3f_xxxx_xxxx_xxxx | 0xXXXX |
| battle.attack | 0x7b2e_xxxx_xxxx_xxxx | 0xXXXX |
| chat.send | 0x9c4f_xxxx_xxxx_xxxx | 0xXXXX |
| chat.receive | 0x1a8b_xxxx_xxxx_xxxx | 0xXXXX |
| auth.login | 0x5d7c_xxxx_xxxx_xxxx | 0xXXXX |
| auth.logout | 0x3e9a_xxxx_xxxx_xxxx | 0xXXXX |

> 注：具体ID需要运行算法生成，上表仅为示例

## 3. 手动映射表设计

### 3.1 设计思路

虽然可以通过hash自动生成ID，但为了：
1. 前后端消息ID一致
2. 便于文档维护
3. 避免hash碰撞

采用**手动维护映射表 + 自动生成**的混合方式。

### 3.2 映射表结构

```java
public class MessageIdRegistry {

    private static final Map<String, Integer> MESSAGE_ID_MAP = new HashMap<>();
    private static final Map<Integer, String> ID_MESSAGE_MAP = new HashMap<>();

    static {
        // 模块：认证
        register("auth.login", 1001);
        register("auth.logout", 1002);
        register("auth.refresh", 1003);

        // 模块：战斗
        register("battle.move", 2001);
        register("battle.attack", 2002);
        register("battle.skill", 2003);

        // 模块：聊天
        register("chat.send", 3001);
        register("chat.receive", 3002);

        // 模块：物品
        register("item.use", 4001);
        register("item.get", 4002);

        // 模块：心跳
        register("heartbeat", 9001);
    }

    public static void register(String messageName, int messageId) {
        MESSAGE_ID_MAP.put(messageName, messageId);
        ID_MESSAGE_MAP.put(messageId, messageName);
    }

    public static int getMessageId(String messageName) {
        return MESSAGE_ID_MAP.getOrDefault(messageName,
            MessageIdGenerator.generateMessageId(messageName));
    }

    public static String getMessageName(int messageId) {
        return ID_MESSAGE_MAP.get(messageId);
    }
}
```

### 3.3 注册流程

1. 开发者在 `MessageIdRegistry` 中注册消息名和ID
2. 如果未注册，自动使用hash生成
3. 生成 `MessageIds.java` 常量类

## 4. 消息ID常量类生成

### 4.1 生成的常量类示例

```java
public final class MessageIds {

    private MessageIds() {
    }

    // 模块：认证 (1000-1099)
    public static final int AUTH_LOGIN = 1001;
    public static final int AUTH_LOGOUT = 1002;
    public static final int AUTH_REFRESH = 1003;

    // 模块：战斗 (2000-2099)
    public static final int BATTLE_MOVE = 2001;
    public static final int BATTLE_ATTACK = 2002;
    public static final int BATTLE_SKILL = 2003;

    // 模块：聊天 (3000-3099)
    public static final int CHAT_SEND = 3001;
    public static final int CHAT_RECEIVE = 3002;

    // 模块：物品 (4000-4099)
    public static final int ITEM_USE = 4001;
    public static final int ITEM_GET = 4002;

    // 模块：心跳 (9000-9099)
    public static final int HEARTBEAT = 9001;

    // 消息名到ID的映射
    public static final Map<String, Integer> NAME_TO_ID = Map.ofEntries(
        Map.entry("auth.login", AUTH_LOGIN),
        Map.entry("auth.logout", AUTH_LOGOUT),
        Map.entry("auth.refresh", AUTH_REFRESH),
        Map.entry("battle.move", BATTLE_MOVE),
        Map.entry("battle.attack", BATTLE_ATTACK),
        Map.entry("battle.skill", BATTLE_SKILL),
        Map.entry("chat.send", CHAT_SEND),
        Map.entry("chat.receive", CHAT_RECEIVE),
        Map.entry("item.use", ITEM_USE),
        Map.entry("item.get", ITEM_GET),
        Map.entry("heartbeat", HEARTBEAT)
    );
}
```

### 4.2 ID段分配建议

| 模块 | ID范围 | 说明 |
|------|--------|------|
| 系统 | 0001-0999 | 认证、心跳、错误码等 |
| 认证 | 1000-1099 | 登录、登出、刷新Token |
| 战斗 | 2000-2099 | 移动、攻击、技能 |
| 聊天 | 3000-3099 | 发送、接收、频道 |
| 物品 | 4000-4099 | 使用、丢弃、交易 |
| 社交 | 5000-5099 | 好友、工会 |
| 任务 | 6000-6099 | 任务相关 |
| 商城 | 7000-7099 | 商城相关 |
| 活动 | 8000-8099 | 活动相关 |
| 心跳 | 9000-9099 | 心跳、ping |
| 预留 | 9100-9999 | 预留扩展 |

## 5. 使用示例

### 5.1 服务端处理

```java
public class GateMessageDispatcher {

    public void dispatch(WrappedMessage message) {
        int messageId = message.getHeader().getMessageId();
        String messageName = MessageIdRegistry.getMessageName(messageId);

        switch (messageName) {
            case "auth.login":
                handleLogin(message);
                break;
            case "battle.move":
                handleBattleMove(message);
                break;
            // ...
        }
    }
}
```

### 5.2 客户端发送

```java
public class PlayerClientSender {

    public void sendMove(int x, int y) {
        WrappedMessage message = new WrappedMessage();
        message.setMessageId(MessageIds.BATTLE_MOVE);

        Map<String, Object> body = Map.of("x", x, "y", y);
        message.setBody(new JsonMessageBody(body));

        channel.writeAndFlush(message);
    }
}
```

---

## 6. 文档维护

### 6.1 消息对照表

| 消息ID | 消息名 | 说明 | 方向 |
|--------|--------|------|------|
| 1001 | auth.login | 登录请求 | C→S |
| 1002 | auth.logout | 登出请求 | C→S |
| 2001 | battle.move | 移动请求 | C→S |
| 3001 | chat.send | 聊天消息 | C→S |
| 9001 | heartbeat | 心跳 | C↔S |

### 6.2 文档自动生成

可以编写脚本从 `MessageIdRegistry` 自动生成Markdown文档：

```bash
# 生成消息对照表
java -jar message-id-gen.jar --output docs/message_table.md
```

---

*文档版本：1.0*
*最后更新：2026-03-06*
