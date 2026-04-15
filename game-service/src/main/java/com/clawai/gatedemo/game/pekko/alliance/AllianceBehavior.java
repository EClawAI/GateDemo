package com.clawai.gatedemo.game.pekko.alliance;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * 每联盟一聚合邮箱；联盟级权威字段（占位清单，持久化落地后续迭代）：
 *
 * <ul>
 *   <li>职位与权限位、入盟/踢人规则
 *   <li>联盟科技、捐献进度
 *   <li>外交状态（宣战/停战等占位）
 * </ul>
 *
 * <p>存储选型：Redis 会话型缓存或 Mongo 文档；以本 Actor 邮箱为写入边界，不与其他聚合共享可变静态状态。
 */
public final class AllianceBehavior {

    private AllianceBehavior() {}

    public sealed interface Command permits Ping {}

    /** 最小健康检查；无外部 IO。 */
    public record Ping(ActorRef<String> replyTo) implements Command {}

    public static Behavior<Command> create(long allianceId) {
        return Behaviors.receive(Command.class)
                .onMessage(
                        Ping.class,
                        p -> {
                            p.replyTo().tell("alliance-" + allianceId + "-ok");
                            return Behaviors.same();
                        })
                .build();
    }
}
