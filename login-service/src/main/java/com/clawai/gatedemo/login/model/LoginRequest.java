package com.clawai.gatedemo.login.model;

import lombok.Data;

/**
 * 登录请求体，承载玩家标识与设备等，作为鉴权与路由链路的输入契约。
 */
@Data
public class LoginRequest {
    private Long playerId;
    /** 客户端携带的第三方或会话令牌，可按业务扩展校验 */
    private String token;
    /** 设备标识，用于风控或多端区分 */
    private String deviceId;
}
