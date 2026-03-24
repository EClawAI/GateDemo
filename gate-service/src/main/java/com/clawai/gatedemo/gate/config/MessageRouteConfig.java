package com.clawai.gatedemo.gate.config;

import com.clawai.gatedemo.common.route.MessageRouteScanner;
import com.clawai.gatedemo.proto.gate.GateProtocol;
import com.clawai.gatedemo.proto.game.GameMessages;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 启动时扫描所有 proto 文件的 custom option，自动填充 {@link com.clawai.gatedemo.common.route.MessageRouteRegistry}。
 * 新增 proto 文件时在此追加对应的 FileDescriptor 即可。
 */
@Configuration
public class MessageRouteConfig {

    @PostConstruct
    public void init() {
        MessageRouteScanner.scan(
                GateProtocol.getDescriptor(),
                GameMessages.getDescriptor()
        );
    }
}
