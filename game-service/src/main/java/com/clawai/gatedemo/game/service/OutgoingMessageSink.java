package com.clawai.gatedemo.game.service;

import java.util.Map;

/**
 * Sink for outgoing game messages (echo to sender, broadcast to all).
 * Used when Game processes messages via stream; allows sending responses back to Gate.
 */
@FunctionalInterface
public interface OutgoingMessageSink {

    /**
     * Emit an outgoing message. Gate interprets:
     * - playerId > 0: send to that player (echo)
     * - playerId == 0: broadcast to all connected players
     */
    void emit(long playerId, int gameId, String msgType, int seq, Map<String, Object> body);
}
