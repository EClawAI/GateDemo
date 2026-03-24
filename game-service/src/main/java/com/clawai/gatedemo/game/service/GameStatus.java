package com.clawai.gatedemo.game.service;

/**
 * 游戏服生命周期与是否允许登录的离散状态，与 Redis 中的状态值对齐，供登录网关判断是否放行玩家。
 */
public enum GameStatus {
    NOT_STARTED(0, "服务未启动"),
    STARTED_NOT_LOGIN(1, "已启动但不可登录"),
    STARTED_CAN_LOGIN(2, "可以登录");
    
    private final int value;
    private final String description;
    
    GameStatus(int value, String description) {
        this.value = value;
        this.description = description;
    }
    
    public int getValue() {
        return value;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static GameStatus fromValue(int value) {
        for (GameStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return NOT_STARTED;
    }
}
