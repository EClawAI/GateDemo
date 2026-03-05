package com.clawai.gatedemo.gate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    private final GateConfig gateConfig;

    public HttpClientConfig(GateConfig gateConfig) {
        this.gateConfig = gateConfig;
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    public String getGameBaseUrl() {
        return "http://" + gateConfig.getGame().getHost() + ":" + gateConfig.getGame().getPort();
    }
}
