package com.clawai.gatedemo.login.controller;

import com.clawai.gatedemo.common.dto.ApiResponse;
import com.clawai.gatedemo.login.model.*;
import com.clawai.gatedemo.login.service.GameRouteService;
import com.clawai.gatedemo.login.service.GateService;
import com.clawai.gatedemo.login.service.JwtTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 登录域 REST 入口：编排 JWT 签发、游戏路由、登录记录与网关心跳/列表，连接客户端与网关/游戏状态。
 */
@RestController
@RequestMapping("/api/v1")
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    private final GameRouteService gameRouteService;
    private final GateService gateService;
    private final JwtTokenService jwtTokenService;

    /**
     * @param gameRouteService 游戏与网关路由决策
     * @param gateService      网关心跳与列表
     * @param jwtTokenService  JWT 签发
     */
    public LoginController(GameRouteService gameRouteService, GateService gateService,
                           JwtTokenService jwtTokenService) {
        this.gameRouteService = gameRouteService;
        this.gateService = gateService;
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * 玩家登录：解析路由结果，返回目标网关、游戏与 JWT；路由失败时返回业务错误码。
     *
     * @param request 含玩家 ID 等登录入参
     * @return 成功时携带 {@link LoginResponse}，失败时为 {@link ApiResponse} 错误体
     */
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
        response.setToken(jwtTokenService.generateToken(request.getPlayerId()));
        
        return ApiResponse.success(response);
    }

    /**
     * 记录玩家最近一次进入的游戏，供后续登录优先路由。
     *
     * @param request 玩家 ID 与游戏 ID
     * @return 统一成功空体
     */
    @PostMapping("/game/login-record")
    public ApiResponse<Void> recordLogin(@RequestBody LoginRecordRequest request) {
        logger.info("Login record: playerId={}, gameId={}", request.getPlayerId(), request.getGameId());
        
        gameRouteService.savePlayerLoginRecord(request.getPlayerId(), request.getGameId());
        
        return ApiResponse.success(null);
    }

    /**
     * 网关周期性上报存活与在线人数，刷新内存与 Redis 中的网关视图。
     *
     * @param request 网关标识、地址、端口与在线数
     * @return 统一成功空体
     */
    @PostMapping("/gate/heartbeat")
    public ApiResponse<Void> gateHeartbeat(@RequestBody GateHeartbeatRequest request) {
        logger.debug("Gate heartbeat: gateId={}, online={}", request.getGateId(), request.getOnline());
        
        gateService.handleHeartbeat(request);
        
        return ApiResponse.success(null);
    }

    /**
     * 查询当前已上报的网关快照（内存视图）。
     *
     * @return 网关 ID 到实例信息的映射
     */
    @GetMapping("/gate/list")
    public ApiResponse<Object> gateList() {
        return ApiResponse.success(gateService.getAllGates());
    }
}
