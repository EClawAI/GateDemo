package com.clawai.gatedemo.login.model;

import lombok.Data;

/**
 * 客户端上报玩家最近进入的游戏，写入持久偏好，供后续登录路由优先复用。
 */
@Data
public class LoginRecordRequest {
    private Long playerId;
    private Integer gameId;
}
