package com.clawai.gatedemo.login.model;

import lombok.Data;

/**
 * 登录请求体，承载玩家标识与设备等，作为鉴权与路由链路的输入契约。
 */
@Data
public class LoginRequest {
    private Long playerId;
    private String token;
    private String deviceId;
}
