package com.clawai.gatedemo.game.pekko;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * Typed guardian for the {@code game} {@link org.apache.pekko.actor.typed.ActorSystem}.
 * Extended behaviors (sessions, world, etc.) will be spawned here in later changes.
 */
public final class GameRootBehavior {

    private GameRootBehavior() {}

    public static Behavior<Void> create() {
        return Behaviors.setup(ctx -> Behaviors.empty());
    }
}
