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
        // 启动客户端
        clientService.connect();
    }
}
