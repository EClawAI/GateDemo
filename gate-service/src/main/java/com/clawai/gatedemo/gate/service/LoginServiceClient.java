package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class LoginServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(LoginServiceClient.class);

    private final GateConfig gateConfig;
    private final RestTemplate restTemplate;

    public LoginServiceClient(GateConfig gateConfig) {
        this.gateConfig = gateConfig;
        this.restTemplate = new RestTemplate();
    }

    @Scheduled(fixedDelayString = "${gate.login-service.heartbeat-interval:30000}")
    public void sendHeartbeat() {
        try {
            String url = String.format("http://%s:%d/api/v1/gate/heartbeat",
                gateConfig.getLoginService().getHost(),
                gateConfig.getLoginService().getPort());

            HeartbeatRequest request = new HeartbeatRequest();
            request.setGateId(gateConfig.getId());
            request.setHost(gateConfig.getHost());
            request.setPort(gateConfig.getPort());
            request.setOnline(getOnlineCount());

            restTemplate.postForObject(url, request, Void.class);

            logger.debug("Heartbeat sent to LoginService: gateId={}, online={}", 
                gateConfig.getId(), request.getOnline());
        } catch (Exception e) {
            logger.warn("Failed to send heartbeat to LoginService: {}", e.getMessage());
        }
    }

    private Integer getOnlineCount() {
        return 0;
    }

    public static class HeartbeatRequest {
        private String gateId;
        private String host;
        private Integer port;
        private Integer online;

        public String getGateId() { return gateId; }
        public void setGateId(String gateId) { this.gateId = gateId; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
        public Integer getOnline() { return online; }
        public void setOnline(Integer online) { this.online = online; }
    }
}
