package com.clawai.gatedemo.login.controller;

import com.clawai.gatedemo.login.model.*;
import com.clawai.gatedemo.login.service.GameRouteService;
import com.clawai.gatedemo.login.service.GateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    private final GameRouteService gameRouteService;
    private final GateService gateService;

    public LoginController(GameRouteService gameRouteService, GateService gateService) {
        this.gameRouteService = gameRouteService;
        this.gateService = gateService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        logger.info("Login request: playerId={}", request.getPlayerId());
        
        GameRouteService.RouteResult result = gameRouteService.route(request.getPlayerId());
        
        if (result.getCode() != 0) {
            return ApiResponse.error(result.getCode(), result.getMessage());
        }
        
        LoginResponse response = new LoginResponse();
        
        LoginResponse.GateInfo gateInfo = new LoginResponse.GateInfo();
        gateInfo.setId(result.getGateId());
        gateInfo.setHost(result.getGateHost());
        gateInfo.setPort(result.getGatePort());
        response.setGate(gateInfo);
        
        response.setGameId(result.getGameId());
        
        return ApiResponse.success(response);
    }

    @PostMapping("/game/login-record")
    public ApiResponse<Void> recordLogin(@RequestBody LoginRecordRequest request) {
        logger.info("Login record: playerId={}, gameId={}", request.getPlayerId(), request.getGameId());
        
        gameRouteService.savePlayerLoginRecord(request.getPlayerId(), request.getGameId());
        
        return ApiResponse.success(null);
    }

    @PostMapping("/gate/heartbeat")
    public ApiResponse<Void> gateHeartbeat(@RequestBody GateHeartbeatRequest request) {
        logger.debug("Gate heartbeat: gateId={}, online={}", request.getGateId(), request.getOnline());
        
        gateService.handleHeartbeat(request);
        
        return ApiResponse.success(null);
    }

    @GetMapping("/gate/list")
    public ApiResponse<Object> gateList() {
        return ApiResponse.success(gateService.getAllGates());
    }
}
