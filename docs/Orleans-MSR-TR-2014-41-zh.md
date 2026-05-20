# Orleans：面向可编程性与可扩展性的分布式 Virtual Actor

**Philip A. Bernstein**, **Sergey Bykov**, **Alan Geller**, **Gabriel Kliot**, **Jorgen Thelin**  
Microsoft Research

---

## 摘要

大规模交互式服务需要高吞吐、低延迟与高可用；在传统无状态三层架构下，这些目标很难同时满足。Actor 模型便于构建有状态 middle tier，从而获得所需性能。但常见的 actor 平台仍把许多分布式系统问题留给开发者。

Orleans 编程模型提出 **virtual actor** 这一抽象，化解了可靠性、分布式资源管理等一类复杂问题，使开发者不必亲自处理。与此同时，Orleans **runtime** 仍能让应用获得高性能、高可靠与可扩展性。

本文阐述 Orleans 背后的设计原则，说明其如何以简单的编程模型达成上述目标；介绍 Orleans 如何简化 Windows Azure 上若干可扩展生产应用的开发；并报告这些生产系统的性能表现。

---

## 1. 引言

构建可扩展且可靠的交互式服务很难。交互性对可用性与延迟约束严格，直接影响终端用户体验。为支撑大量并发会话，高吞吐必不可少。传统三层架构——无状态前端、无状态 middle tier 与存储层——受限于每次请求都要访问存储层所带来的延迟与吞吐上限。人们常在 middle tier 与存储之间加缓存以提升性能 [9][14][19]。但缓存会丧失底层存储层的大部分并发与语义保证。为防止对同一缓存项的并发更新导致不一致，应用或缓存管理器必须实现并发控制协议 [11]。无论是否有缓存，无状态 middle tier 都不具备数据局部性，因为它采用 **data shipping**：每个请求都要把数据从存储或缓存拉到处理该请求的 middle tier 服务器。社交网络图中单次请求可能经多跳动态触达大量实体，要在保证应用层语义与一致性的同时，用缓存对交互式访问快速响应，更加困难。

**Actor 模型**依托 **function shipping** 范式，为上述挑战提供了有吸引力的解法。Actor 便于构建有状态 middle tier：既有带数据局部性的缓存式性能，又可通过面向应用的操作封装实体，获得语义与一致性上的好处。此外，actor 也便于在 middle tier 实现实体之间的横向、“社交化”关联。

从面向对象编程（OOP）视角看分布式系统的可编程性也很自然。OOP 适合对复杂系统建模，但在流行的面向服务架构（SOA）下被边缘化；实现服务组件时仍可采用 OOP，但在系统层面开发者往往要以松耦合、分区服务的思路思考，与应用概念对象脱节，加大了主流开发者构建分布式系统的难度。Actor 模型把 OOP 拉回到系统层面：对开发者而言，actor 很像熟悉的交互对象模型。

Erlang [3]、Akka [2] 等 **actor 平台**在简化分布式编程上前进了一步，但由于抽象与系统服务层级仍偏低，许多分布式复杂性仍落在开发者身上：在应用代码中管理 **actor** 生命周期、应对固有分布式竞态、处理故障与恢复、**actor** 放置与分布式资源管理等。要在应用中正确解决这些问题，开发者几乎必须是分布式专家。

为规避这些复杂性，我们构建了 Orleans 编程模型与 **runtime**，抬高了 **actor** 抽象的层级。Orleans 面向并非分布式专家的开发者（专家用户也很喜欢）。它基于 actor，但与既有 actor 平台不同：把 **actor** 视为**虚拟实体**而非物理实体。第一，Orleans **actor** 在虚拟意义上**始终存在**，不能显式创建或销毁；其存在超越任意内存中实例乃至任意单机寿命。第二，Orleans **actor** **自动实例化**：若某 **actor** 没有内存中实例，发往该 **actor** 的消息会在可用服务器上创建新实例；闲置实例会作为 **runtime** 资源管理的一部分被自动回收。**Actor** 本身“不会挂”：若服务器 S 崩溃，下一个发往曾在 S 上运行的 **actor** A 的消息会促使 Orleans 在另一台服务器上自动重新实例化 A，应用无需监督或显式重建失败 **actor**。第三，**actor** 实例位置对应用代码**透明**，大幅简化编程。第四，Orleans 可对同一无状态 **actor** 自动创建多个实例，对热点 **actor** 无缝 **scale out**。

总体上，Orleans 为开发者提供类似虚拟内存的虚拟“**actor** 空间”，可调用系统中任意 **actor**，无论其当前是否在内存中。虚拟化依赖从虚拟 **actor** 到当前运行中物理实例的间接映射；这层间接使 **runtime** 有机会代开发者解决放置与负载均衡、闲置 **actor** 停用、服务器故障后 **actor** 恢复等难题——这些若由应用来做极易出错。因此 **virtual actor** 路径既简化编程模型，又使 **runtime** 能透明地做负载均衡与故障恢复。

间接通过**分布式目录**实现。Orleans 在每台机器上用本地缓存维护 **actor** 标识到当前物理位置的映射，压低间接成本；该策略很有效，生产环境中缓存命中率通常远高于 90%。

Orleans 已用于构建运行在 Microsoft Windows Azure 上的多个生产服务，包括若干热门游戏的后端；我们据此验证了采用 Orleans 的生产应用在扩展性与可靠性上的表现，并根据反馈调整模型与实现；也有迹象表明 Orleans 能显著提高开发效率。

Orleans 编程模型适用于多种应用，但有些模式并不契合：例如在大量实体上频繁批量操作与单实体操作交织的应用——**actor** 隔离使批量代价比共享内存结构更高。若系统中 **actor** 数量极大（数十亿级）且缺乏时间局部性，**virtual actor** 模型也可能变差。Orleans 尚不支持跨 **actor** 事务，因此强依赖数据库外跨实体事务的应用不适合。

综上，本文主要贡献为：

(a) 新型的 **virtual actor** 抽象与随之简化的编程模型；

(b) 分布式 **actor** 模型的一种高效、可扩展实现，在较好性能与扩展性的同时减轻传统 actor 框架的部分编程负担；

(c) 生产实践中的详细测度与经验。

文章结构：

第 2 节介绍 Orleans 编程模型；

第 3 节描述 **runtime**，侧重 **virtual actor** 如何支撑扩展性与可靠性；

第 4 节讨论实践用法；

第 5 节给出生产与合成基准的测试；

第 6 节将 Orleans 与其他 actor 框架及文献 [5] 中早期原型对比；

第 7 节为结论。

---

## 2. 编程模型

本节描述 Orleans 编程模型，并给出来自 Halo 4 Presence 服务（第 4.1 节详述）的代码示例。

### 2.1 Virtual Actor

Orleans 编程模型基于 .NET Framework 4.5 [10]。**Actor** 是 Orleans 应用的基本构造块，也是隔离与分布的单位。每个 **actor** 有唯一标识，由类型与主键（128 位 GUID）组成。**Actor** 封装行为与可变状态，与对象类似；状态可通过内置持久化设施保存。**Actor** 彼此隔离、不共享内存，因此只能通过收发消息交互。

Orleans 中对 **actor** 的虚拟化有四个侧面：

1. **永存（Perpetual existence）**：**Actor** 是纯逻辑实体，在虚拟意义上始终存在；不能显式创建或销毁，虚拟存在也不受执行它的服务器故障影响。因始终存在且始终可寻址。

2. **自动实例化（Automatic instantiation）**：Orleans **runtime** 自动创建内存中的 **actor** 实例，称为 **activation**。任一时刻，一个 **actor** 可有零个或多个 **activation**；若无待处理请求则可能不被实例化。向当前未实例化的 **actor** 发新请求时，**runtime** 会选服务器、在该机实例化实现该 **actor** 的 .NET 对象，并调用 `ActivateAsync` 做初始化。若 **actor** 当前所在服务器故障，下次调用时 **runtime** 会在新服务器上自动重新实例化。因此 Orleans 不需要 Erlang [3]、Akka [2] 那种由应用负责重建失败 **actor** 的 **supervision tree**。闲置 **actor** 的内存实例会被 **runtime** 回收，并调用 `DeactivateAsync` 以便清理。

3. **位置透明（Location transparency）**：**Actor** 可在不同时刻位于不同位置，也可能没有物理位置；与 **actor** 交互或在 **actor** 内运行的代码不知道其物理位置。这类似虚拟内存：逻辑页可随时间映射到不同物理地址，也可能被换出。如同 OS 从磁盘按需装入页面，Orleans **runtime** 会在新请求到达时自动实例化尚未在内存中的 **actor**。

4. **自动扩展（Automatic scale out）**：目前 Orleans 对 **actor** 类型支持两种 **activation** 模式：默认的 **single activation**（同一 **actor** 仅允许一个并发 **activation**），以及 **stateless worker**（由 Orleans 按需在上限内自动创建多个彼此独立的 **activation** 以提高吞吐）。“独立”指同一 **actor** 的不同 **activation** 之间不做状态调和；因此 **stateless worker** 适合不可变或无状态的 **actor**（例如只读缓存）。

把 **actor** 做成虚拟实体而非物理实体，深刻影响 Orleans 的编程模型与实现。自动 **activation**、位置透明与永存使应用无需显式激活/停用 **actor**、监督生命周期或在故障时重建。

### 2.2 Actor Interface

**Actor** 通过强类型接口中声明的方法与属性相互调用。接口中方法与属性必须异步，返回类型须为 **promise**（见第 2.4 节）。

```csharp
public interface IGameActor : IActor
{
    Task<string> GameName { get; }
    Task<List<IPlayerActor>> CurrentPlayers { get; }
    Task JoinGame(IPlayerActor game);
    Task LeaveGame(IPlayerActor game);
}
```

### 2.3 Actor Reference

**Actor reference** 是强类型的虚拟 **actor** 代理，供其他 **actor** 或非 **actor** 代码调用其方法与属性。可调用 Orleans 在编译期生成的工厂类的 `GetActor` 并指定主键获得引用；也可从远端方法或属性返回值得到引用；引用可作为 **actor** 方法调用的参数传递。

```csharp
public static class GameActorFactory
{
    public static IGameActor GetActor(Guid gameId);
}
```

**Actor reference** 是虚拟的：不向程序员暴露目标 **actor** 的位置，也不存在传统 RPC（如 Java RMI、CORBA、WCF）中常见的“绑定”概念。在 Orleans 中，发送方在本地创建引用后即可使用，无需 bind/register；这既简化编程，又允许对 **actor** 的请求立刻流水线化，无需等待解析服务端点。

### 2.4 Promise

**Actor** 通过异步消息交互；与现代分布式编程模型类似，消息交换表现为方法调用。但与常见模型不同，Orleans 的方法调用立即返回的是代表未来结果的 **promise**，而非阻塞至返回。**Promise** 带来并发而无需显式管线程。

**Promise** 有三态生命周期：初始为未决；收到结果后为已履行，结果为值；计算或通信出错则为已断裂。

在 API 上，**promise** 体现为 `System.Threading.Tasks.Task<T>`（某类型的最终值）或 `System.Threading.Tasks.Task`（对应 void 的完成 **promise**）。

使用 **promise** 的主要方式是在其解决时调度闭包（或 continuation）。通常通过对 **promise** 使用 C# 的 `await` 隐式完成。下面示例中编译器做栈撕裂，把 `await` 之后的代码变成在 **promise** 解决后执行的闭包，从而使异步代码看起来像顺序代码且便于编写错误处理。

```csharp
IGameActor gameActor = GameActorFactory.GetActor(gameId);
try {
    string name = await gameActor.GameName;
    Console.WriteLine("Game name is " + name);
} catch (Exception) {
    Console.WriteLine("The call to actor failed");
}
```

### 2.5 Turn

**Actor activation** 单线程工作，以称为 **turn** 的片段为单位；一次只执行一个 **turn**。**Turn** 可以是方法调用，也可以是 **promise** 解决时执行的闭包。不同 **activation** 的 **turn** 可并行，但同一 **activation** 任意时刻只有一个 **turn**。

基于 **turn** 的异步模型允许同一 **activation** 上多个请求的 **turn** 交错；推理交错较难，因此 Orleans 默认会等 **activation** 处理完一个请求（包括该请求 processing 期间产生的所有 **promise** 均解决且相关闭包跑完）再派发下一请求。若要改变该行为，可在 **actor** 类上标注 `[Reentrant]`：允许在处理前一请求的 **turn** 间隙处理另一请求（例如等待挂起 IO）。两类请求的 **turn** 仍保证单线程一次一 **turn**，但可属于不同请求而自由交错。

### 2.6 Persistence

处理请求可能改变 **actor** 状态，状态可持久也可不持久。Orleans 提供简化持久化管理的机制：**actor** 类可声明表示需持久化状态的 property bag；**runtime** 为该类每个 **actor** 提供实现该接口的状态对象及持久化/刷新方法。

```csharp
// State property bag interface
public interface IGameState : IState
{
    GameStatus Status { get; set }
    List<IPlayerActor> Players { get; set;}
}

// Actor class implementation
public class GameActor : ActorBase<IGameState>, IGameActor
{
    Task JoinGame(IPlayerActor game)
    {
        // Update state property bag
        this.State.Players.Add(game);
        // Checkpoint actor state
        return this.State.WriteStateAsync();
    }
}
```

何时对 **actor** 持久状态做 **checkpoint** 由应用决定：可在每次请求结束时，或按定时器、自上次 **checkpoint** 以来的请求数等周期执行。

与底层存储的交互通过 **persistence provider** 完成，适配 SQL、列存、blob 等具体存储。

### 2.7 Timer 与 Reminder

Orleans 有两类定时能力。**Transient timer** 近似 .NET 定时器接口，但保证单线程执行语义；它绑定在 **activation** 上，**actor** 停用即消失。

**Reminder** 则在 **actor** 是否处于活动状态时都可能触发，超越创建它的那个 **activation**，直至显式删除。若 **Reminder** 触发时 **actor** 未激活，会像普通消息一样自动创建新的 **activation** 来处理。**Reminder** 是可靠的持久定时器：既能为创建它的 **actor** 产生消息，又允许 **runtime** 在两次触发之间必要时停用 **actor** 以回收资源。其语义与“系统中永久存在、仅在需要处理请求时才在内存中 **activation**”的 **virtual actor** 一致，适合在容错前提下做低频周期工作，且无需把 **activation** 永远钉在内存里。

---

## 3. Runtime 实现

本节概述 **runtime** 总体架构、关键设计取舍与理由；指导原则是在不牺牲性能的前提下保持编程模型简单。

### 3.1 概览

Orleans 运行在数据中心服务器集群上，每台机器运行容器进程，创建并托管 **actor activation**。单台服务器有三个关键子系统：**Messaging**、**Hosting**、**Execution**。**Messaging** 在每对服务器之间维持一条 TCP 连接，用一组通信线程在已建连接上多路复用 **actor** 间消息。**Hosting** 决定 **activation** 放置并管理生命周期。**Execution** 在一组计算线程上运行应用代码，并保证单线程与可重入语义。

当 **actor** 调用另一 **actor** 时，**Execution** 将调用转为消息，连同目标 **actor** 标识交给 **Messaging**；**Messaging** 与 **Hosting** 协作确定目标服务器。**Hosting** 维护分布式目录，记录集群中所有 **activation**；要么找到已有 **activation**，要么选服务器创建新 **activation**。**Messaging** 序列化消息并经已打开套接字发往对端；接收端反序列化并组编成强类型对象交给 **Execution** 调度执行。若 **actor** 正忙，请求入队直至前一调用完成。若接收方需新建 **activation**，先在目录注册再创建本地内存实例；**single activation** 保证由目录执行。

**Hosting** 也负责本机资源管理：**actor** 闲置超过可配置时间或内存吃紧时，**runtime** 自动 **deactivate** 并回收资源。该简单策略依赖 **actor** 虚拟化：未用 **actor** 可在任意服务器本地独立回收，之后再透明 **re-activate**，无需复杂的分布式垃圾回收去追踪指向 **actor** 的所有物理引用。

### 3.2 分布式目录

许多分布式系统用确定性放置（一致性哈希或区间划分）避免显式目录。Orleans 允许完全灵活放置，把每个 **actor** 的位置记在分布式目录中，使 **runtime** 能随负载迁移 **actor**，更自由地管理资源。

Orleans 目录实现为一跳 **DHT** [17]：每台服务器持有一部分目录分区，**actor** 用一致性哈希映射到分区；记录将 **actor** id 映射到其 **activation** 位置。新建 **activation** 时向对应分区发注册请求；**deactivate** 时发注销。**Single-activation** 由目录强制：若单 **activation** **actor** 已有注册，新注册被拒并返回已有 **activation** 地址。

目录带来每条消息多一跳的开销；因此 Orleans 在每台服务器维护大容量本地缓存，缓存近期解析过的 **actor**→**activation** 映射。单 **activation** **actor** 的缓存项约 80 字节，典型生产机可轻松缓存数百万条；生产中缓存命中率很高，几乎消弭了“每条消息额外一跳”的需求。

### 3.3 强隔离

Orleans 中 **actor** 不共享状态、彼此隔离；仅能通过 **actor reference** 上的方法调用所体现的消息通信。方法调用的参数与返回值在 **actor** 调用间同步 **deep copy**，即便两台 **actor** 在同一台机器上，以保证所传数据不可变视图。

为降低 **deep copy** 成本，Orleans 采用两种互补手段：其一，在方法签名中用 `Immutable<T>` 标注参数，表示调用方不会修改，**runtime** 可跳过拷贝——适合 Router **actor**（Halo 4 Presence，第 4.1 节）那种只解压数据 blob、不保存、不修改的场景。其二，必须拷贝时使用序列化子系统中高度优化的拷贝模块（第 3.7 节）。

### 3.4 异步

Orleans 强制异步风格，用 **promise** 表示未来结果；所有 **actor** 方法调用异步，返回类型须为 `Task` 或 `Task<T>`。.NET 4.5 的 `async`/`await` 大大简化了 **promise** 代码。

广泛采用异步对应用简洁性与可扩展性很重要：避免应用线程在等待结果时空转，使远程请求成本对系统吞吐影响最小。测试中增加分布会提高延迟（更多机外调用），但在通信密集型应用中几乎不影响吞吐。

### 3.5 单线程

Orleans 保证每个 **activation** 内至多一个线程运行，故 **activation** 状态不会并发访问，无需锁等同步原语；该保证由 **Execution** 提供，且不为每个 **activation** 建线程。单 **activation** 性能有上限，但大量 **activation** 并行足以吃满 CPU，并往往带来更好的整体响应与吞吐。

### 3.6 协作式多任务

Orleans 用**协作式多任务**调度应用 **turn**：**turn** 一旦开始会跑到结束、不被抢占。**Orleans scheduler** 用少量受控计算线程（通常等于 CPU 核数）执行所有 **actor** 代码。

单机上要支撑数万到数百万 **actor**，若每个 **activation** 一个线程做抢占式多任务，线程数会超出硬件与 OS 能力；即便未超限，数千线程的抢占调度也会因上下文切换与缓存局部性变差而性能低下。协作式多任务让大量 **activation** 在少量线程上高效运行；我们也曾在 25 台服务器满载、CPU 90%+ 多日压测下保持稳定。

协作式的弱点是行为不良的组件可能长时间占满一个 CPU、拖累其他组件；Orleans 当前不面向多租户，**actor** 同属一方开发者，问题相对可控。**Runtime** 对过长 **turn** 提供监控与通知；生产中一般不常见。

### 3.7 序列化

将复杂对象编组为字节流再还原是分布式系统的核心；对开发者隐藏但严重影响性能。Protocol Buffers [15] 等方案速度快但限制可传类型；许多序列化器不支持动态类型、任意多态或对象标识（反序列化后两指针是否仍指同一对象）。标准 .NET 二进制序列化支持标了 `[Serializable]` 的类型，但慢且体积大。

为兼顾可编程性，Orleans 允许任意数据类型并在序列化中保持对象标识；结构体、数组、完全多态与泛型对象均可使用。我们通过编译期生成自定义序列化代码，对 .NET 集合等常见类型手工优化，在“标准”类型上与顶尖序列化器竞争；序列化结果紧凑、动态类型元数据尽量少。

### 3.8 可靠性

Orleans 自动管理可靠性诸多方面，程序员无需显式处理；例外是 **actor** 持久状态，仍由开发者负责。

**Runtime** 内置成员（membership）机制管理服务器：周期心跳检测故障并就成员视图达成一致。故障后短时期内不同服务器上的成员视图可能分歧，但最终会一致；收敛时间取决于检测配置，生产一般配置为约 30–60 秒内完成。被成员服务判定死亡的服务器会自行关闭，即使只是暂时网络问题。

服务器故障时，其上所有 **activation** 丢失；若目录分区未复制，失败机上的目录信息也丢失。幸存机获知故障后会扫描目录分区与本地缓存，清除指向失效机上 **activation** 的项。由于 **actor** 是虚拟的，**服务器挂了不等于 actor 挂了**：下一次请求会在幸存机上新建 **activation**；**actor** 寿命与宿主服务器寿命解耦。

故障是否丢状态取决于应用：Orleans 不强制 **checkpoint** 策略。应用可每次更新内存状态后 **checkpoint** 并等待确认再向调用方返回成功，这样故障重激活可自最近 **checkpoint** 恢复；也可代表现实设备、仅作设备状态的缓存，由设备消息更新，则不必 **checkpoint**，重激活后由设备后续数据重建。若可容忍少量更新丢失，也可固定间隔周期 **checkpoint**。结合不同后端存储 **provider**，可在可靠性与性能间折中。

路由所用目录信息可能不准：本地缓存陈旧、注销失败等。Orleans 不要求目录绝对准确；消息被错投时，接收方会重路由或退回发送方；双方会刷缓存或更新目录项。若目录与某已存在 **activation** 失联，新请求会创建新 **activation**，旧 **activation** 最终会被停用。

### 3.9 最终一致性

无故障时 Orleans 保证 **actor** 只有一个 **activation**；故障期间只能**最终**保证。

成员关系在“某机已死但尚未全员知晓”期间处于波动：带过期成员视图的注册请求可能被误投；目标会重路由到正确分区所有者。也可能同一 **single-activation** **actor** 在两个分区各注册了一个 **activation**；成员稳定后，目录会去掉其中一个并向对应服务器发停用消息。

我们倾向于可用性优先于强一致，以便成员波动时应用仍能推进。对多数应用，“最终单 **activation**”语义足够，且情况罕见。若不够，可依赖外部持久存储提供更强一致性；实践表明靠恢复与调和比追求目录绝对准确、缓存严格一致更简单、稳健且更快。

### 3.10 消息语义

Orleans 提供 **at-least-once** 投递：可配置超时后对未确认消息重发。**Exactly-once** 可通过持久化已投递消息 id 加入，但我们认为成本过高且多数应用不需要，仍可在应用层实现。

分布式经验常认为 **FIFO** 有序廉价且有用：发送方序号、消息头与接收队列即可。我们最初也保证 **actor** A 到 B 的消息有序，但在 **actor** 极多时，每对 **actor** 的状态为 O(n²)，难以扩展；且多数请求–响应应用不需要 **FIFO**。开发者可通过握手表达依赖：收到上一条回复再发下一条；无顺序要求则可并行发出。**FIFO** 因此我们移出默认保证。

---

## 4. 应用

Orleans 已被多个团队用于构建云上的生产服务，包括 Halo 4 的全部云服务。本节介绍两款游戏中三个服务：它们用 Orleans 实现游戏后端不同部分，使用模式与性能特征各异。生产规模与许多性能数字涉密，故仅报告预生产阶段在实验室的测量。

### 4.1 Halo 4 Presence Service

**Presence service** 跟踪所有活跃对局、参与者与进行中的游戏状态；改善匹配、支持加入进行中对局、实时观战等。每台运行 Halo 4 的主机会定期发 **heartbeat** 上报对局状态；频率由服务控制，可因伴侣 App 实时观战等场景提高。另有查询、加入会话等调用，本文只讨论 **heartbeat**。

多人对局中各主机独立发带状态更新的 **heartbeat**；会话状态不写 durable 存储，仅存内存——真相始终在主机上，任意一名玩家一次 **heartbeat** 即可在故障后恢复会话。**Heartbeat** 负载含压缩会话数据：会话 ID、玩家 ID 等，处理前需解压。

![image-20260408111329188](https://eric-typora-img.oss-cn-beijing.aliyuncs.com/typora/image-20260408111329188.png)

结构见图 1。有三类 **actor**：Router、Game Session、Player。主机 **heartbeat** 先入 Router **actor**，解压、取会话 ID 后转发到对应 Session **actor**；每个会话 ID 对应一个 Game Session **actor**。Session 更新内存状态，并周期性地（非每次 **heartbeat**）根据玩家 ID 调用 Player **actor**。Player **actor** 也作为外部观察者（如手机伴侣应用）查找用户当前对局的汇合点：观察者先按用户 ID 调 Player **actor**，得到当前 Game Session **actor** 引用，再直接向 Game Session **actor** 订阅实时通知。

Router 无状态，Orleans 对该逻辑 **actor** 在每机最多创建与 CPU 核数相当的多个 **activation**，且总在接收请求的本机，省一跳网络。另三类为 **single activation** 模式，任意时刻 0 或 1 个 **activation**，随机散布在各服务器。

**Virtual actor** 使调用代码很简单：按类型与标识拿引用，立刻调方法；无需定位、实例化或处理服务器故障。

### 4.2 Halo 4 Statistics Service

统计服务处理已完赛与进行中对局的重要事件（命中、武器、地图位置等），支撑成就、段位、个人统计、匹配等；也处理来自主机与官网的查询。统计极重要，玩家不能接受成就丢失；因此上报先入 Windows Azure Service Bus [18] 可靠队列，以便服务器故障时可恢复处理。

![image-20260408111352454](https://eric-typora-img.oss-cn-beijing.aliyuncs.com/typora/image-20260408111352454.png)

图 2 为简化架构（省略次要组件）。前端收到含统计数据的 HTTP 请求后写入 Azure Service Bus 队列；另一组 **worker** 拉取请求并按会话 ID 调用对应 Game Session **actor**；Orleans 路由到某 **activation**，必要时新建。Game Session **actor** 先把载荷原样存 Azure BLOB，再拆解并分发给统计中列出的各 Player **actor**；各 Player 处理后可写 Azure Table，供后续状态与成就查询。

写存储的操作幂等，失败可安全重放；处理超时或异常时，出队 **worker** 可重投。

对局期间 Game **actor** 常驻内存，在缓存中累积部分统计至终局合并；Player **actor** 在处理统计或服务查询期间也常驻并缓存玩家数据，减少存储 IO 与延迟。

### 4.3 Galactic Reign Services

Galactic Reign 是手机与 PC 上的回合制对战游戏；玩家提交回合指令，游戏推进回合。使用四类 **actor**：有状态的 Game Session，以及无状态的 Video Manager、Housekeeper、Notification。Game Session 在收到双方指令后执行逻辑，结果 **write-through** 到持久 Azure Storage，再请求视频渲染服务生成本回合短视频（最长约 90 秒）。

![image-20260408111414638](https://eric-typora-img.oss-cn-beijing.aliyuncs.com/typora/image-20260408111414638.png)

每个 Game Session **actor** 缓存当前会话状态；状态可达数 MB，从存储加载较慢，故活跃会话尽量驻留内存；不活跃会话由 **runtime** 随时间 **deactivate**，需要时再 **re-activate**。

Video Manager **actor** 池向渲染系统提交任务并接收完成通知；无状态，**runtime** 可透明增加 **activation**；它们用 **timer** 轮询完成作业并转给 Notification **actor**。

回合完成且视频生成后，Notification **actor** 通知设备上的客户端；Housekeeper **actor** 用 **timer** 周期唤醒，清理已放弃对局的持久数据。

### 4.4 Database Session Pooling

分布式系统中常见难题是共享资源（数据库、队列、硬件）的访问：N 个前端或 middle tier 与 M 个分片各自建连会产生 N×M 条连接，N、M 达数百时可能超出负载均衡器等网络限制。

Orleans 便于实现共享连接池：引入 **Shard** 类型 **actor** 封装到某数据库分片的已打开连接；应用通过 **Shard** **actor** 代理发请求而非直连，并通过哈希让每个分片映射到少量 **Shard** **actor**，从而控制连接数。**Virtual actor** 还会在故障后自动 **reactivate** 代理。此场景中 Orleans 实现有状态、动态、可扩展、容错的连接池。

---

## 5. 性能

本节研究 Orleans 性能：先针对方方面面做合成微基准，再运行第 4.1 节 Halo Presence 的生产代码做整机测试。微基准各跑 5 次、每次 10 分钟；生产性能评估各 30 分钟。

测量在最多 125 台服务器的集群上进行：每台两台 AMD 四核 Opteron 2.10GHz（共 8 核）、32GB RAM，64 位 Windows Server 2008 R2 与 .NET 4.5。

### 5.1 合成微基准

**异步 IO 与协作式多任务。** 评估异步消息与协作式多任务掩盖 **actor** 工作延迟的效果。1000 个 **actor**，多负载发生器压满系统；每请求模拟 **actor** 调用另一 **actor** 或外部存储，并改变模拟延迟。远程调用异步，当前请求不阻塞，调用线程可处理其他请求。

![image-20260408111456235](https://eric-typora-img.oss-cn-beijing.aliyuncs.com/typora/image-20260408111456235.png)

图 4 表明： **actor** 内模拟外部调用延迟升高对整体吞吐影响很小。

**协作式多任务与线程。** Orleans **scheduler** 通常用与 CPU 数相当的少量计算线程做协作式多任务，比大量线程更高效。用短 **ping** 消息与不同线程数做吞吐测试，结果见图 5：线程变多时吞吐因上下文切换、额外内存、更长调度队列与缓存局部性下降而稳步恶化。

![image-20260408111633010](https://eric-typora-img.oss-cn-beijing.aliyuncs.com/typora/image-20260408111633010.png)

**隔离的代价。** Orleans **actor** 隔离意味着调用参数需 **deep copy**。基准中客户端调第一个 **actor**，再以其参数（一次原样、一次 `Immutable` 免拷贝）调第二个；50% 本地、50% 远端。远端比例越大，**deep copy** 带来的吞吐下降相对越小，因为序列化与远程消息开销占比上升；数百台服务器上的大应用多数调用为远端，拷贝代价会缩小。

表 1 给出三种类型的 **deep copy** 代价（请求吞吐）：简单 `byte[]` 约 4%；字典仍低于约 10%；若字典元素为可变复杂类型，开销显著增大。

| 参数类型 | 说明 | Don’t Copy | Copy | 降幅 |
|----------|------|------------|------|------|
| Byte[] | 100 字节数组 | 7300 | 7000 | 4.3% |
| Dictionary<int, string> | 100 个元素 | 6300 | 5700 | 9.5% |
| Dictionary<int, List<int>> | 100 个元素，每个 list 长 1 | 6500 | 3400 | 47.7% |

### 5.2 Halo Presence 性能评估

**随服务器数量扩展。** 在测试集群运行 Halo 4 Presence 生产 **actor** 代码，100 万个 **actor**；用足够负载发生器以 **heartbeat** 流量饱和 Orleans  节点，测最大可持续吞吐。节点全程稳定于约 95–97% CPU。每次 **heartbeat** 至少两次 **RPC**：客户端→Router **actor**，Router→Session **actor**；第一次总为远端，第二次因 Session **actor** 随机放置通常也为远端。

![image-20260408111741774](https://eric-typora-img.oss-cn-beijing.aliyuncs.com/typora/image-20260408111741774.png)

图 6：25 台约 13 万次 **heartbeat**/秒（约每台 5200）；扩至 125 台时接近线性扩展。

**随 actor 数量扩展。** 固定 25 台，多负载发生器饱和系统。

![image-20260408111809332](https://eric-typora-img.oss-cn-beijing.aliyuncs.com/typora/image-20260408111809332.png)

图 7：**actor** 从 2 千增至 200 万，吞吐几乎不变；极大数量时略有下降，系内部数据结构变大。

**负载与延迟。** 固定 25 台，增加负载发生器测 **heartbeat** 延迟。

![image-20260408111823242](https://eric-typora-img.oss-cn-beijing.aliyuncs.com/typora/image-20260408111823242.png)

图 8 横轴为 25 台平均 CPU 利用率：中位延迟在 CPU 约 19% 以下约 6.5 ms，34%、55% 时约 10 ms、15 ms。每次 **heartbeat** 含两次 **RPC** 与 CPU 密集的 blob 解压；少数 **heartbeat** 还会触发文中未写到的额外 **actor**，多一跳与 CPU 工作使它们的延迟更高，拉高了均值、标准差与 95 分位。

---

## 6. 相关工作

将 Orleans 与通用分布式编程框架及其他 **actor** 框架对比。

### 6.1 分布式编程框架

Orleans 可跑在 Windows Azure 与 Windows Server，但当前应用多在 Azure，可与各类云开发框架比较。Google App Engine（GAE）与 Orleans 都提供面向对象模型，但有两处主要不同：GAE 是 Java/Python 同步 RPC + 多线程；Orleans 是 **actor** 模型、异步 RPC + 单线程。其次 Orleans 不绑定特定数据库服务，GAE 内置带事务的数据库服务。

EJB、DCOM、CORBA 等分布式对象模型与 **actor** 框架有相似之处；但与 Orleans 不同，它们以同步通信为主（EJB 也有 Message-Driven Bean 等异步形态），要求静态放置对象（类到服务器或分区映射），允许多线程服务器与对象共享状态；没有 **virtual actor** 抽象，但提供事务、可靠消息、请求队列、发布订阅等 Orleans 未涵盖的能力。

大量工作通过缓存改进多层架构性能 ([9] [11] [13] [14] [16] [19])，但一致性与完整性语义落到应用；**function shipping** 与 **actor** 模型可缓解该问题。

### 6.2 Actor 框架

Orleans 借鉴多种既有 **actor** 系统。文献 [7] 归纳 JVM **actor** 框架五个性质：状态封装、安全消息传递（按值 **deep copy**）、位置透明、迁移、公平调度。Orleans 完全支持前三项；支持弱迁移——**actor** 可换机但不能在处理请求途中换；尽力公平调度——行为良好的应用中每个 **actor** 可得约公平 CPU。

Erlang [3] 是带 **actor** 模型的函数式语言，其 **actor** 称 **process**。与 Orleans 类似：单线程、逻辑引用、单向消息；原则上只有私有状态，实践中常经表或注册表共享。与 Orleans 不同，Erlang **actor** 显式创建，`spawn` 可在本机或远程创建，之后位置固定，无法享受 Orleans 的动态负载均衡、**actor** 迁移与故障后自动换机重启。

Erlang 用 `link` 显式控制错误传播；无链路的进程未处理异常会默默退出。Orleans 中异常经 **promise** 沿分布式调用链自动传播。

OTP 在 **runtime** 层提供容错、分布与并发抽象；可选监督树记录进程创建树，子进程失败可由 supervisor 重建或杀兄弟再重建——灵活但要求开发者显式管理每个 **actor** 生命周期。Orleans 无创建层次，**actor** 由 **runtime** 自动创建与回收；服务器故障后在别机自动重建，编程简单得多。

Akka [2] 面向 Java/Scala。与 Orleans 类似：单线程、私有状态、逻辑引用；**at-most-once** 投递与任意两 **actor** 间 **FIFO**。与 Erlang 类似，**actor** 显式创建，创建层次驱动异常处理；逻辑名为反映监督层级的路径，远程引用用物理路径，创建时位置固定，难以动态均衡、迁移与容灾。

Akka 有 Orleans 未覆盖的能力，如运行时向 **actor** 装载新代码、以及保证同一机器上一组调用原子性的本地事务机制——后者不适用于分布式 **actor** 模型。

文献 [5] 的 Orleans 早期原型未完全实现 **virtual actor**：需显式生命周期管理；每次调用自动持久化状态，对生产用户过贵，从而演化为第 2.6 节的持久化机制；**promise**/continuation 语法较冗长，后改为 .NET 4.5 `async`/`await` 与相应 **runtime**；曾提供多主复制多 **activation** 方案，因性能与用户复杂度而放弃；[5] 仅有微基准，无第 5 节规模的生产场景。

**其他 actor 模型。** Kilim [16] 侧重单节点，用线程切换模拟 **actor** 与通信。ActorFoundry [1] 在 **actor** 间用同步 send/receive，而非 Orleans 的异步 continuation 式 API。Thorn [4]（与 Erlang）接口动态弱类型，需仔细对齐收发语义；Orleans 强类型接口便于编译期检查。Monterey [12] 用 key 取 **actor** 引用，与 Orleans 类似，但需显式生命周期，允许同步通信与多线程 **actor**——Orleans 两者都不允许。

---

## 7. 结论

本文介绍 Orleans：用于构建可靠、高效、可扩展云应用的框架；阐述 **virtual actor** 抽象及其对可编程性的益处，并讨论实现与生产用法及性能测度。

Orleans 可沿多条路径扩展以简化平台能力接入与丰富服务：我们优先考虑消息 **exactly-once**、事件流、主副本复制与事务等；这些技术虽已成熟，但要使之对普通开发者足够可靠、高效且可扩展，仍需创新。

---

## 8. 参考文献

[1] ActorFoundry, http://osl.cs.uiuc.edu/af/

[2] Akka documentation, http://akka.io/docs/

[3] Armstrong, J.: Erlang. CACM, 53, 9 (Sept. 2010), 68-75

[4] Bloom, B., Field, J., Nystrom, N., Östlund, J., Richards, G., Strnisa, R., Vitek, J. and Wrigstad, T. Thorn: Robust, Concurrent, Extensible Scripting on the JVM. In OOPSLA 2009, 117-136.

[5] Bykov, S., Geller, A., Kliot, G., Larus, J., Pandya, R., and Thelin, J.: Orleans: Cloud Computing for Everyone. In SOCC 2011, 16:1-16:14.

[6] Eker, J., Janneck, J. W., Lee, E. A., Jie, L., Xiaojun, L., Ludvig, J., Neuendorffer, S., Sachs, S. and Yuhong, X. Taming: Heterogeneity - the Ptolemy Approach. Proc. of the IEEE 91, 1 (January 2003), 127-144.

[7] Karmani, R. K., Shali, A. and Agha, G.: Actor Frameworks for the JVM Platform: A Comparative Analysis. In Proc.of the 7th Int'l Conf. on the Principles and Practice of Prog. in Java, 2009, 11-20.

[8] Liskov, B. and Shrira, L. Promises: Linguistic Support for Efficient Asynchronous Procedure Calls in Distributed Systems. In SIGPLAN 1988, 260-267.

[9] Memcached, http://memcached.org/

[10] Microsoft .NET, http://www.microsoft.com/net/

[11] Miller, M. S., Tribble, E. D. and Shapiro, J.: Concurrency Among Strangers: Programming in E as Plan Coordination. In Proc. of the Int'l Symp. on Trustworthy Global Computing, 2005, Springer, 195-229.

[12] Monterey, http://www.cloudsoftcorp.com/products/monterey/

[13] Ports, D.R.K., T. Clements, I. Zhang, S. Madden, and B. Liskov: Transactional Consistency and Automatic Management in an Application Data Cache. OSDI 2010: 279-292

[14] Power, R., and J. Li: Piccolo: Building Fast, Distributed Programs with Partitioned Tables. OSDI 2010: 293-306

[15] Protocol Buffers, http://code.google.com/p/protobuf/

[16] Srinivasan, Sriram and Mycroft, Alan, Kilim: Isolation-Typed Actors for Java, European Conference on Object-Oriented Programming, 2008

[17] Stoica, I., Morris, R., Karger D.R., Kaashoek, M. F., Balakrishnan, H.: Chord: A scalable peer-to-peer lookup service for internet applications. SIGCOMM 2001: 149-160.

[18] Windows Azure Service Bus, http://www.windowsazure.com/en-us/home/features/messaging/

[19] Windows Azure Cache, http://www.windowsazure.com/en-us/documentation/services/cache/

---

*原文：Microsoft Research Technical Report — Orleans: Distributed Virtual Actors for Programmability and Scalability (MSR-TR-2014-41)。正文据 PDF 整理译为中文；图位保留「补充插图」说明。英文版见 `Orleans-MSR-TR-2014-41-en.md`。*
