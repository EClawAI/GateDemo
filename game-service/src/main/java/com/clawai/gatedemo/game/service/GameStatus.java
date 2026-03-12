package com.clawai.gatedemo.game.service;

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
