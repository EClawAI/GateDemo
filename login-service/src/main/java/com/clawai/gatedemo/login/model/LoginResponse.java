package com.clawai.gatedemo.login.model;

import lombok.Data;

@Data
public class LoginResponse {
    private GateInfo gate;
    private Integer gameId;
    private String gameHost;
    private Integer gamePort;
    private Boolean redirect;
    private String redirectMessage;

    @Data
    public static class GateInfo {
        private String id;
        private String host;
        private Integer port;
    }
}
