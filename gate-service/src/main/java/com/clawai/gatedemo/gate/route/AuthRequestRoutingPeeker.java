package com.clawai.gatedemo.gate.route;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.proto.gate.AuthRequest;
import org.springframework.stereotype.Component;

/**
 * 对齐 icefire-gate {@code CgLoginRoutingPeeker}：从首包认证消息的 protobuf 体解析目标 game。
 * GateDemo 使用 {@link AuthRequest#getGameId()}（对照 Thrift CgLogin.serverId）。
 */
@Component
public class AuthRequestRoutingPeeker {

    private static final int MSG_ID_AUTH = MessageRouteRegistry.getIdByName("AuthRequest");

    /**
     * @return 目标 gameId；非认证消息或解析失败时返回 {@code <= 0}
     */
    public int extractGameId(WrappedMessage msg) {
        if (msg == null || msg.getHeader() == null) {
            return -1;
        }
        if (msg.getHeader().getMessageId() != MSG_ID_AUTH) {
            return -1;
        }
        try {
            byte[] bodyBytes = msg.getBody() != null ? msg.getBody().toBytes() : new byte[0];
            AuthRequest login = AuthRequest.parseFrom(bodyBytes);
            int gid = login.getGameId();
            if (gid <= 0) {
                return -1;
            }
            return gid;
        } catch (Exception e) {
            return -1;
        }
    }
}
