package com.clawai.gatedemo.login.model;

import lombok.Data;

@Data
public class LoginRequest {
    private Long playerId;
    private String token;
    private String deviceId;
}
