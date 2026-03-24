package com.clawai.gatedemo.login.model;

import lombok.Data;

/**
 * 网关心跳请求体，用于登录服务刷新网关存活与在线人数，支撑选路与运维可见性。
 */
@Data
public class GateHeartbeatRequest {
    private String gateId;
    private String host;
    private Integer port;
    private Integer online;
}
