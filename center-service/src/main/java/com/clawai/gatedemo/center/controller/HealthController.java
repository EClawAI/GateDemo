package com.clawai.gatedemo.center.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量存活探针，供负载均衡与编排判断 center-service 进程是否可对外服务。
 */
@RestController
public class HealthController {

    /**
     * 进程存活探针，不检查外部依赖。
     *
     * @return {@code status=UP} 与 HTTP 200
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        return ResponseEntity.ok(result);
    }
}
