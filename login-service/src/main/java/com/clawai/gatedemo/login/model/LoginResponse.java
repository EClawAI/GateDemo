package com.clawai.gatedemo.login.model;

import lombok.Data;

/**
 * 登录成功响应体，聚合网关地址、目标游戏、JWT 及跨服跳转提示，与客户端进服流程对齐。
 */
@Data
public class LoginResponse {
    private GateInfo gate;
    private Integer gameId;
    /** 直连游戏服时的主机，与 {@link #gate} 二选一或补充使用 */
    private String gameHost;
    /** 直连游戏服时的端口 */
    private Integer gamePort;
    /** 是否因上次服不可用而切换到推荐服 */
    private Boolean redirect;
    /** 切换服时展示给玩家的提示文案 */
    private String redirectMessage;
    /** 供网关或游戏服校验的玩家 JWT */
    private String token;

    @Data
    public static class GateInfo {
        private String id;
        private String host;
        private Integer port;
    }
}
