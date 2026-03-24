package com.clawai.gatedemo.login.model;

import lombok.Data;

/**
 * 登录成功响应体，聚合网关地址、目标游戏、JWT 及跨服跳转提示，与客户端进服流程对齐。
 */
@Data
public class LoginResponse {
    private GateInfo gate;
    private Integer gameId;
    private String gameHost;
    private Integer gamePort;
    private Boolean redirect;
    private String redirectMessage;
    private String token;

    @Data
    public static class GateInfo {
        private String id;
        private String host;
        private Integer port;
    }
}
