# Actor 生命周期

> 原文：[https://pekko.apache.org/docs/pekko/1.3/typed/actor-lifecycle.html](https://pekko.apache.org/docs/pekko/1.3/typed/actor-lifecycle.html)
>
> 本文档描述的是新版 Actor API，如需查看 Pekko Classic 文档，请参阅 Classic Actors。

## 依赖

要使用 Pekko Actor Typed，你需要在项目中添加以下依赖：

```xml
<properties>
  <scala.binary.version>2.13</scala.binary.version>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.apache.pekko</groupId>
      <artifactId>pekko-bom_${scala.binary.version}</artifactId>
      <version>1.3.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>org.apache.pekko</groupId>
    <artifactId>pekko-actor-typed_${scala.binary.version}</artifactId>
  </dependency>
</dependencies>
```

## 简介

Actor 是一种有状态的资源，必须显式地启动和停止。

需要特别注意的是，**Actor 不会在没有引用时自动停止**，每个创建的 Actor 都必须显式销毁。唯一的简化是：停止父 Actor 会递归地停止该父 Actor 创建的所有子 Actor。当 `ActorSystem` 关闭时，所有 Actor 也会自动停止。

> **注意**
>
> `ActorSystem` 是一个重量级结构，会分配线程，因此每个逻辑应用程序只应创建一个。通常每个 JVM 进程一个 `ActorSystem`。

## 创建 Actor

一个 Actor 可以创建（spawn）任意数量的子 Actor，子 Actor 又可以创建自己的子 Actor，从而形成 Actor 层级结构。`ActorSystem` 承载整个层级结构，且只能有一个**根 Actor**（位于 `ActorSystem` 层级结构顶部的 Actor）。子 Actor 的生命周期与父 Actor 绑定——子 Actor 可以自行停止或随时被停止，但永远不会比父 Actor 存活更久。

### ActorContext

`ActorContext` 可用于多种目的，例如：

- 创建子 Actor 和监督（supervision）
- 监视（watch）其他 Actor，在被监视的 Actor 永久停止时接收 `Terminated(otherActor)` 事件
- 日志记录
- 创建消息适配器（message adapter）
- 与另一个 Actor 进行请求-响应交互（ask）
- 访问 `getSelf()` 获取自身的 `ActorRef`

如果一个 Behavior 需要使用 `ActorContext`（例如创建子 Actor 或使用 `context.getSelf()`），可以通过 `Behaviors.setup` 包装来获取：

```java
public class HelloWorldMain extends AbstractBehavior<HelloWorldMain.SayHello> {
  public static Behavior<SayHello> create() {
    return Behaviors.setup(HelloWorldMain::new);
  }

  private final ActorRef<HelloWorld.Greet> greeter;

  private HelloWorldMain(ActorContext<SayHello> context) {
    super(context);
    greeter = context.spawn(HelloWorld.create(), "greeter");
  }
}
```

#### ActorContext 的线程安全性

`ActorContext` 中的许多方法**不是线程安全的**：

- **不能**在 `java.util.concurrent.CompletionStage` 的回调中访问
- **不能**在多个 Actor 实例之间共享
- **只能**在普通的 Actor 消息处理线程中使用

### 守护 Actor（Guardian Actor）

顶层 Actor，也称为**用户守护 Actor**（user guardian actor），与 `ActorSystem` 一同创建。发送给 Actor 系统的消息会被定向到根 Actor。根 Actor 由创建 `ActorSystem` 时使用的 Behavior 定义，在下面的示例中名为 `HelloWorldMain`：

```java
final ActorSystem<HelloWorldMain.SayHello> system =
    ActorSystem.create(HelloWorldMain.create(), "hello");

system.tell(new HelloWorldMain.SayHello("World"));
system.tell(new HelloWorldMain.SayHello("Pekko"));
```

对于非常简单的应用程序，守护 Actor 可以包含实际的应用逻辑并处理消息。一旦应用需要处理多个关注点，守护 Actor 应该只负责引导应用程序——作为子 Actor 创建各个子系统，并监控它们的生命周期。

当守护 Actor 停止时，`ActorSystem` 也会随之停止。

当调用 `ActorSystem.terminate` 时，协调关闭（Coordinated Shutdown）流程会按特定顺序停止 Actor 和服务。

### 创建子 Actor（Spawning Children）

子 Actor 通过 `ActorContext` 的 `spawn` 方法创建和启动。在下面的示例中，当根 Actor 启动时，它创建了一个由 `HelloWorld` Behavior 描述的子 Actor。此外，当根 Actor 接收到 `SayHello` 消息时，它会创建一个由 `HelloWorldBot` Behavior 定义的子 Actor：

```java
public class HelloWorldMain extends AbstractBehavior<HelloWorldMain.SayHello> {

  public static class SayHello {
    public final String name;

    public SayHello(String name) {
      this.name = name;
    }
  }

  public static Behavior<SayHello> create() {
    return Behaviors.setup(HelloWorldMain::new);
  }

  private final ActorRef<HelloWorld.Greet> greeter;

  private HelloWorldMain(ActorContext<SayHello> context) {
    super(context);
    greeter = context.spawn(HelloWorld.create(), "greeter");
  }

  @Override
  public Receive<SayHello> createReceive() {
    return newReceiveBuilder().onMessage(SayHello.class, this::onStart).build();
  }

  private Behavior<SayHello> onStart(SayHello command) {
    ActorRef<HelloWorld.Greeted> replyTo =
        getContext().spawn(HelloWorldBot.create(3), command.name);
    greeter.tell(new HelloWorld.Greet(command.name, replyTo));
    return this;
  }
}
```

要在创建 Actor 时指定调度器（dispatcher），可以使用 `DispatcherSelector`。如果未指定，Actor 将使用默认调度器，详情参见 Default dispatcher。

```java
public class HelloWorldMain extends AbstractBehavior<HelloWorldMain.SayHello> {

  // Start message...

  public static Behavior<SayHello> create() {
    return Behaviors.setup(HelloWorldMain::new);
  }

  private final ActorRef<HelloWorld.Greet> greeter;

  private HelloWorldMain(ActorContext<SayHello> context) {
    super(context);

    final String dispatcherPath = "pekko.actor.default-blocking-io-dispatcher";
    Props greeterProps = DispatcherSelector.fromConfig(dispatcherPath);
    greeter = getContext().spawn(HelloWorld.create(), "greeter", greeterProps);
  }

  // createReceive ...
}
```

关于上述示例的详细介绍，请参阅 Actors 章节。

### SpawnProtocol

守护 Actor 应负责初始化任务并创建应用程序的初始 Actor，但有时你可能希望从守护 Actor 的外部创建新 Actor。例如，为每个 HTTP 请求创建一个 Actor。

这在你的 Behavior 中不难实现，但由于这是一种常见模式，Pekko 提供了预定义的消息协议和 Behavior 实现。它可以作为 `ActorSystem` 的守护 Actor 使用，还可以与 `Behaviors.setup` 组合来启动一些初始任务或 Actor。然后可以通过 `tell` 或 `ask` `SpawnProtocol.Spawn` 消息到系统的 Actor 引用来从外部启动子 Actor。使用 `ask` 类似于经典 Actor 中 `ActorSystem.actorOf` 的用法，区别在于返回的是 `ActorRef` 的 `CompletionStage`。

守护 Behavior 可以定义为：

```java
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

public abstract class HelloWorldMain {
  private HelloWorldMain() {}

  public static Behavior<SpawnProtocol.Command> create() {
    return Behaviors.setup(
        context -> {
          // Start initial tasks
          // context.spawn(...)

          return SpawnProtocol.create();
        });
  }
}
```

然后可以使用该 `main` Behavior 创建 `ActorSystem`，并通过 ask 请求来创建其他 Actor：

```java
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

final ActorSystem<SpawnProtocol.Command> system =
    ActorSystem.create(HelloWorldMain.create(), "hello");
final Duration timeout = Duration.ofSeconds(3);

CompletionStage<ActorRef<HelloWorld.Greet>> greeter =
    AskPattern.ask(
        system,
        replyTo ->
            new SpawnProtocol.Spawn<>(HelloWorld.create(), "greeter", Props.empty(), replyTo),
        timeout,
        system.scheduler());

Behavior<HelloWorld.Greeted> greetedBehavior =
    Behaviors.receive(
        (context, message) -> {
          context.getLog().info("Greeting for {} from {}", message.whom, message.from);
          return Behaviors.stopped();
        });

CompletionStage<ActorRef<HelloWorld.Greeted>> greetedReplyTo =
    AskPattern.ask(
        system,
        replyTo -> new SpawnProtocol.Spawn<>(greetedBehavior, "", Props.empty(), replyTo),
        timeout,
        system.scheduler());

greeter.whenComplete(
    (greeterRef, exc) -> {
      if (exc == null) {
        greetedReplyTo.whenComplete(
            (greetedReplyToRef, exc2) -> {
              if (exc2 == null) {
                greeterRef.tell(new HelloWorld.Greet("Pekko", greetedReplyToRef));
              }
            });
      }
    });
```

`SpawnProtocol` 也可以在 Actor 层级结构的其他位置使用，不一定非要是根守护 Actor。

发现运行中 Actor 的方法在 Actor discovery 章节中描述。

## 停止 Actor

Actor 可以通过返回 `Behaviors.stopped()` 作为下一个 Behavior 来停止自身。

父 Actor 可以通过 `ActorContext` 的 `stop` 方法强制子 Actor 在处理完当前消息后停止。只有子 Actor 才能以这种方式被停止。

当父 Actor 停止时，所有子 Actor 都会被停止。

当 Actor 停止时，它会收到 `PostStop` 信号，可用于清理资源。

下面是一个示例：

```java
import java.util.concurrent.TimeUnit;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;


public class MasterControlProgram extends AbstractBehavior<MasterControlProgram.Command> {

  interface Command {}

  public static final class SpawnJob implements Command {
    public final String name;

    public SpawnJob(String name) {
      this.name = name;
    }
  }

  public enum GracefulShutdown implements Command {
    INSTANCE
  }

  public static Behavior<Command> create() {
    return Behaviors.setup(MasterControlProgram::new);
  }

  public MasterControlProgram(ActorContext<Command> context) {
    super(context);
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
        .onMessage(SpawnJob.class, this::onSpawnJob)
        .onMessage(GracefulShutdown.class, message -> onGracefulShutdown())
        .onSignal(PostStop.class, signal -> onPostStop())
        .build();
  }

  private Behavior<Command> onSpawnJob(SpawnJob message) {
    getContext().getSystem().log().info("Spawning job {}!", message.name);
    getContext().spawn(Job.create(message.name), message.name);
    return this;
  }

  private Behavior<Command> onGracefulShutdown() {
    getContext().getSystem().log().info("Initiating graceful shutdown...");

    // 这里可以执行优雅停止（可能是异步的），完成后
    // 在这里或收到另一条消息后返回 `Behaviors.stopped()`。
    return Behaviors.stopped();
  }

  private Behavior<Command> onPostStop() {
    getContext().getSystem().log().info("Master Control Program stopped");
    return this;
  }
}
```

```java
public class Job extends AbstractBehavior<Job.Command> {

  interface Command {}

  public static Behavior<Command> create(String name) {
    return Behaviors.setup(context -> new Job(context, name));
  }

  private final String name;

  public Job(ActorContext<Command> context, String name) {
    super(context);
    this.name = name;
  }

  @Override
  public Receive<Job.Command> createReceive() {
    return newReceiveBuilder().onSignal(PostStop.class, postStop -> onPostStop()).build();
  }

  private Behavior<Command> onPostStop() {
    getContext().getSystem().log().info("Worker {} stopped", name);
    return this;
  }
}
```

在 `PostStop` 中清理资源时，你还应考虑对 `PreRestart` 信号做同样的处理——该信号在 Actor 重启时发出。注意，`PostStop` 在重启时**不会**被发出。

## 监视 Actor（Watching Actors）

为了在另一个 Actor 终止（即永久停止，而非临时故障后重启）时收到通知，Actor 可以 `watch`（监视）另一个 Actor。当被监视的 Actor 终止时（参见"停止 Actor"），监视者会收到 `Terminated` 信号。

```java
public class MasterControlProgram extends AbstractBehavior<MasterControlProgram.Command> {

  interface Command {}

  public static final class SpawnJob implements Command {
    public final String name;

    public SpawnJob(String name) {
      this.name = name;
    }
  }

  public static Behavior<Command> create() {
    return Behaviors.setup(MasterControlProgram::new);
  }

  public MasterControlProgram(ActorContext<Command> context) {
    super(context);
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
        .onMessage(SpawnJob.class, this::onSpawnJob)
        .onSignal(Terminated.class, this::onTerminated)
        .build();
  }

  private Behavior<Command> onSpawnJob(SpawnJob message) {
    getContext().getSystem().log().info("Spawning job {}!", message.name);
    ActorRef<Job.Command> job = getContext().spawn(Job.create(message.name), message.name);
    getContext().watch(job);
    return this;
  }

  private Behavior<Command> onTerminated(Terminated terminated) {
    getContext().getSystem().log().info("Job stopped: {}", terminated.getRef().path().name());
    return this;
  }
}
```

`watch` 的替代方案是 `watchWith`，它允许指定一个自定义消息来替代 `Terminated`。这通常比使用 `watch` 和 `Terminated` 信号更受欢迎，因为可以在消息中包含额外信息，以便后续接收时使用。

下面是与上面类似的示例，但使用 `watchWith`，并在作业完成后回复原始请求者：

```java
public class MasterControlProgram extends AbstractBehavior<MasterControlProgram.Command> {

  interface Command {}

  public static final class SpawnJob implements Command {
    public final String name;
    public final ActorRef<JobDone> replyToWhenDone;

    public SpawnJob(String name, ActorRef<JobDone> replyToWhenDone) {
      this.name = name;
      this.replyToWhenDone = replyToWhenDone;
    }
  }

  public static final class JobDone {
    public final String name;

    public JobDone(String name) {
      this.name = name;
    }
  }

  private static final class JobTerminated implements Command {
    final String name;
    final ActorRef<JobDone> replyToWhenDone;

    JobTerminated(String name, ActorRef<JobDone> replyToWhenDone) {
      this.name = name;
      this.replyToWhenDone = replyToWhenDone;
    }
  }

  public static Behavior<Command> create() {
    return Behaviors.setup(MasterControlProgram::new);
  }

  public MasterControlProgram(ActorContext<Command> context) {
    super(context);
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
        .onMessage(SpawnJob.class, this::onSpawnJob)
        .onMessage(JobTerminated.class, this::onJobTerminated)
        .build();
  }

  private Behavior<Command> onSpawnJob(SpawnJob message) {
    getContext().getSystem().log().info("Spawning job {}!", message.name);
    ActorRef<Job.Command> job = getContext().spawn(Job.create(message.name), message.name);
    getContext().watchWith(job, new JobTerminated(message.name, message.replyToWhenDone));
    return this;
  }

  private Behavior<Command> onJobTerminated(JobTerminated terminated) {
    getContext().getSystem().log().info("Job stopped: {}", terminated.name);
    terminated.replyToWhenDone.tell(new JobDone(terminated.name));
    return this;
  }
}
```

注意 `replyToWhenDone` 如何被包含在 `watchWith` 消息中，并在后续收到 `JobTerminated` 消息时使用。

被监视的 Actor 可以是任意 `ActorRef`，不一定必须是子 Actor（如上例所示）。

需要注意的是，终止消息的生成与注册和终止的顺序无关。特别是，即使被监视的 Actor 在注册时已经终止，监视者仍会收到终止消息。

多次注册不一定会导致生成多条消息，但也不保证只收到恰好一条：如果被监视 Actor 的终止已经生成并入队了消息，而在该消息被处理之前又进行了另一次注册，则会入队第二条消息——因为对已终止 Actor 注册监视会立即生成终止消息。

也可以使用 `context.unwatch(target)` 取消对另一个 Actor 生存状态的监视。即使终止消息已经入队到邮箱中，这也有效；调用 `unwatch` 后，该 Actor 的终止消息将不再被处理。

当被监视的 Actor 位于已从集群中移除的节点上时，终止消息也会被发送。
