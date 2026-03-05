package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 游戏服务 HTTP 客户端（纯 Java 实现）
 * 
 * 功能说明：
 * 使用 Java 11+ 的 HttpClient 发送 HTTP 请求到 Game 服务
 * 不依赖 Spring RestTemplate
 * 
 * @author clawAI
 * @since 2026-03-05
 */
public class GameHttpClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数
     * 
     * @param gateConfig Gate 配置
     */
    public GameHttpClient(GateConfig gateConfig) {
        this.baseUrl = String.format("http://%s:%d", 
            gateConfig.getGame().getHost(), 
            gateConfig.getGame().getPort());
        
        // 创建 HTTP 客户端
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))  // 连接超时 5 秒
            .build();
        
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 发送 POST 请求
     * 
     * @param path 请求路径
     * @param body 请求体（Java 对象）
     * @throws IOException IO 异常
     * @throws InterruptedException 中断异常
     */
    public void post(String path, Object body) throws IOException, InterruptedException {
        // 1. 将对象转换为 JSON
        String json = objectMapper.writeValueAsString(body);
        
        // 2. 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))  // 请求超时 10 秒
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        
        // 3. 发送请求（同步）
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        // 4. 检查响应状态
        if (response.statusCode() != 200) {
            throw new RuntimeException("Game service returned: " + response.statusCode());
        }
    }
}