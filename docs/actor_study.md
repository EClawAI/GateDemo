# Actor 自研学习：参考开源项目的成熟思路

本文面向**自研实现**而非直接使用现成框架：从开源里抽出**可复用的设计思路**，再在自有框架里用更简单的方式落地。

---

## 1. 理论 / 规格：先建立词汇表

这些不是「必须用」，但能解释「为什么虚 Actor、为什么单线程信箱」：

| 来源 | 学什么 |
|------|--------|
| **Orleans: Distributed Virtual Actors for Programmability and Scale**（Microsoft 论文） | 虚拟 Actor、turn 串行、按需激活、可插拔存储——「联盟可本地可远程」本质上是**寻址 + 激活/生命周期**问题。 |
| **Akka 文档里的 Actor lifecycle、mailbox、supervision** | 真实进程内 Actor：邮箱、单次处理一条消息、监督树——自研时再裁剪。 |
| **Your Server as a Function**（Twitter FlatMap 那类，可选） | 把「请求 → 纯变换 → 效果」和消息驱动结合时的函数式视角。 |

Orleans 论文与后续延展材料在官网/学术镜像都可搜到；读**摘要 + 虚拟 Actor 定义 + 单线程语义**即可。

---

## 2. 大项目：不直接用，但适合对着设计读

读它们的**公开设计文档、协议、模块边界**，比读全部源码更划算。

| 项目 | 建议抓的「成熟思路」 |
|------|----------------------|
| **Apache Pekko / Akka** | `ActorRef` 与逻辑路径分离；邮箱类型；`tell`/`ask` 语义；Cluster Sharding 里 **EntityId → Shard → Node** 的两级映射（可缩成「EntityId → Local vs Remote 适配器」）。文档：**Cluster Sharding**、**Serialization**、**Message delivery**。 |
| **Microsoft Orleans** | **Grain 类型 + 主键** 的全局地址；客户端 **GrainReference** 与位置透明的调用；**同步上下文单线程**（等价于 turn）；Reminder、State 持久化接口与 Grain 解耦。.NET 源码可读性尚可，重点看 `Orleans.Runtime` 里激活与调度。 |
| **Orbit（EA）** | JVM 上对 Orleans 思路的映射：**Addressable、Actor 代理、远程与本地统一调用路径**；可看文档与少量核心类，学习如何把「同一接口」绑到不同传输。 |
| **Erlang/OTP** | **进程 = Actor**；**按名字注册**（`register`）类似逻辑地址；**跨节点按 `{Name, Node}` 发送**——和「抹平本地/远程」同一类原语；监督树是做「玩家 Actor 挂了谁重启」时的参考。 |

---

## 3. 小而专：适合一行行跟读的实现

| 项目 | 学什么 |
|------|--------|
| **Fibry**（Java，虚拟线程 Actor） | 进程内 **mailbox + 单消费者执行模型** 可以做得多薄；无集群时的最小闭环。 |
| **Quasar / Pulsar（已停更，仅作历史参考）** | **fiber + mailbox** 与「看起来像同步、实际是协作式多路」的折中；理解「别自己用裸锁顶一个语言级 Actor」即可。 |
| **Netty 线程模型** | 单 `EventLoop` 串行处理 channel——**和「一个逻辑槽位单线程」同构**；若玩家 Actor 跑在绑定线程上，可对照 Netty 的思考方式。 |

---

## 4. 与「单进程玩家 + 可本地可远程业务 + 调用抹平」对应的自学 checklist

把大系统拆成自研时要实现的子问题，再分别找参考：

1. **稳定地址**：`ActorType + BusinessKey`（如 `Guild:123`）。→ 学 Orleans Grain 身份、Akka 的 path/entityId。
2. **解析**：本地表 vs 远程注册中心/RPC。→ 学 Sharding 的路由表、Orleans 的 silo 目录。
3. **统一调用面**：接口 + `LocalInvoker` / `RemoteInvoker`。→ 学 Grain 代理、Orbit 的 Actor 工厂/代理。
4. **单次语义**：单线程 turn、禁止并发改同一聚合状态。→ 学 Orleans turn、Akka mailbox。
5. **消息边界**：哪些可序列化、版本演进。→ 学 Pekko 的 `Serializer`、`manifest`、演进策略。

每一点都可以在文档里找到**独立章节**，不必吞掉整个框架。

---

## 5. 推荐阅读顺序（自研路线）

1. **Orleans 虚拟 Actor 论文**（半天）：建立「地址、激活、单线程 turn」概念。
2. **Pekko Cluster Sharding 文档**（浏览 + 画图）：只画 **EntityId → 物理位置** 这一条链。
3. **Fibry 或小体积 Actor demo**（可选）：用手写一个「队列 + 单线程 while」的最小 Actor，对照 Fibry。
4. **Orbit 文档「Actors / Addressables」**：看 JVM 上如何表述「统一调用」。

这样是在**借鉴思路**（ addressing、调度、边界、失败域），而不是绑定依赖；与「大部分单进程 game-server + 少数业务可拆服 + 调用抹平」是同一套问题的子集实现。

---

## 参考链接（便于检索）

- Orleans 论文：`Orleans Distributed Virtual Actors for Programmability and Scale`
- Apache Pekko：https://pekko.apache.org/
- Microsoft Orleans：https://github.com/dotnet/orleans
- Orbit：https://github.com/orbit/orbit · https://docs.orbit.cloud/
- Fibry：https://github.com/lucav76/Fibry
