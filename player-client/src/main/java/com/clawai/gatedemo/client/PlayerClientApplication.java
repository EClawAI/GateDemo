package com.clawai.gatedemo.client;

import com.clawai.gatedemo.client.service.PlayerClientService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PlayerClientApplication implements CommandLineRunner {

    private final PlayerClientService clientService;

    public PlayerClientApplication(PlayerClientService clientService) {
        this.clientService = clientService;
    }

    public static void main(String[] args) {
        SpringApplication.run(PlayerClientApplication.class, args);
    }

    @Override
    public void run(String... args) {
        // 启动客户端（异步连接，不阻塞启动）
        new Thread(() -> {
            try {
                clientService.connect();
            } catch (Exception e) {
                System.err.println("无法连接到 Gate 服务：" + e.getMessage());
                System.err.println("请确保 Gate 服务已启动：cd gate-service && mvn spring-boot:run");
            }
        }).start();
    }
}
