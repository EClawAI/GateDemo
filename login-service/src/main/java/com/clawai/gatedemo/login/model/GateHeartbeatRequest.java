package com.clawai.gatedemo.login.model;

import lombok.Data;

@Data
public class GateHeartbeatRequest {
    private String gateId;
    private String host;
    private Integer port;
    private Integer online;
}
