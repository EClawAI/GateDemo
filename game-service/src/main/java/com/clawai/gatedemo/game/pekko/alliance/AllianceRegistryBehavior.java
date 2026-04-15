package com.clawai.gatedemo.game.pekko.alliance;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.HashMap;
import java.util.Map;

/** 按 {@code allianceId} 懒创建 {@link AllianceBehavior}，对外提供转发入口。 */
public final class AllianceRegistryBehavior {

    public sealed interface Command permits ForwardToAlliance {}

    public record ForwardToAlliance(long allianceId, AllianceBehavior.Command message) implements Command {}

    public static Behavior<Command> create() {
        return Behaviors.setup(ctx -> new AllianceRegistryBehavior(ctx).running());
    }

    private final ActorContext<Command> context;
    private final Map<Long, ActorRef<AllianceBehavior.Command>> alliances = new HashMap<>();

    private AllianceRegistryBehavior(ActorContext<Command> context) {
        this.context = context;
    }

    private Behavior<Command> running() {
        return Behaviors.receive(Command.class)
                .onMessage(ForwardToAlliance.class, this::onForward)
                .build();
    }

    private Behavior<Command> onForward(ForwardToAlliance f) {
        ActorRef<AllianceBehavior.Command> ref =
                alliances.computeIfAbsent(
                        f.allianceId(), id -> context.spawnAnonymous(AllianceBehavior.create(id)));
        ref.tell(f.message());
        return Behaviors.same();
    }
}
