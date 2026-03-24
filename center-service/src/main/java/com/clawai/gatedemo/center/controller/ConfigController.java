package com.clawai.gatedemo.center.controller;

import com.clawai.gatedemo.center.config.CenterConfig;
import com.clawai.gatedemo.common.dto.ApiResponse;
import com.clawai.gatedemo.center.model.ConfigResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 将 {@link CenterConfig} 聚合为对外配置接口，供客户端启动或定时拉取统一视图。
 */
@RestController
@RequestMapping("/api/v1")
public class ConfigController {

    private static final Logger logger = LoggerFactory.getLogger(ConfigController.class);

    private final CenterConfig centerConfig;

    public ConfigController(CenterConfig centerConfig) {
        this.centerConfig = centerConfig;
    }

    @GetMapping("/config")
    public ApiResponse<ConfigResponse> getConfig() {
        logger.info("收到配置请求");
        
        ConfigResponse response = ConfigResponse.fromConfig(centerConfig);
        
        return ApiResponse.success(response);
    }
}
