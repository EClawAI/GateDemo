package com.clawai.gatedemo.game.service;

/**
 * 游戏服生命周期与是否允许登录的离散状态，与 Redis 中的状态值对齐，供登录网关判断是否放行玩家。
 */
public enum GameStatus {
    /** 进程未就绪或已关闭。 */
    NOT_STARTED(0, "服务未启动"),
    /** 已启动但维护门禁，不允许玩家登录。 */
    STARTED_NOT_LOGIN(1, "已启动但不可登录"),
    /** 正常运行且允许登录。 */
    STARTED_CAN_LOGIN(2, "可以登录");
    
    /** 写入 Redis/注册信息的整型编码。 */
    private final int value;
    private final String description;
    
    GameStatus(int value, String description) {
        this.value = value;
        this.description = description;
    }
    
    /**
     * @return 与外部系统约定的状态码
     */
    public int getValue() {
        return value;
    }
    
    /**
     * @return 人类可读说明（日志展示）
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * 按数值反查枚举；未知值时回退 {@link #NOT_STARTED}。
     *
     * @param value Redis 或协议中的状态码
     * @return 对应枚举，默认 NOT_STARTED
     */
    public static GameStatus fromValue(int value) {
        for (GameStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return NOT_STARTED;
    }
}
