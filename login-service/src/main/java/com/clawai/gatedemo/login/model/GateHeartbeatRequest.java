package com.clawai.gatedemo.login.model;

import lombok.Data;

/**
 * 网关心跳请求体，用于登录服务刷新网关存活与在线人数，支撑选路与运维可见性。
 */
@Data
public class GateHeartbeatRequest {
    /** 网关实例唯一标识 */
    private String gateId;
    /** 客户端可连接的网关主机 */
    private String host;
    /** 网关对外端口 */
    private Integer port;
    /** 当前网关上的在线连接数，用于低负载选路 */
    private Integer online;
}
