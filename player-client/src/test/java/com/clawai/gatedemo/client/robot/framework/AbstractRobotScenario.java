package com.clawai.gatedemo.client.robot.framework;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 所有 e2e robot 场景的基类。
 *
 * <p>共性约束：
 * <ul>
 *   <li>带 {@code @Tag("e2e")}，{@code player-client/pom.xml} 中 surefire 默认 excludedGroups=e2e；
 *       普通 {@code mvn test} 与脚本 unit 段都不会触发；</li>
 *   <li>{@code scripts/run-tests.sh} e2e 段用 {@code -Dgroups=e2e -DexcludedGroups=} 覆盖；</li>
 *   <li>在 {@link #afterEach()} 阶段关闭所有通过 {@link #newRobot()} 创建的 client。</li>
 * </ul>
 *
 * <p>子类一律通过 {@link #newRobot()} 申请 robot 实例，不自己 new；
 * 通过 {@link #login} 获取 token / gateInfo 后调 {@code robot.auth(...)}。
 *
 * <p><b>说明</b>：曾尝试用 {@code @EnabledIfEnvironmentVariable} 做门控，但该注解
 * 不是 {@code @Inherited}，写在 abstract 父类上对子类无效。最终改用 {@code @Tag} +
 * surefire group 过滤，更可靠且无侵入。
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractRobotScenario {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractRobotScenario.class);

    protected RobotEnvironment env;
    protected LoginClient login;

    private final List<RobotClient> opened = new ArrayList<>();

    @BeforeAll
    public void setupEnv() {
        this.env = RobotEnvironment.fromSystem();
        this.login = new LoginClient(env.loginUrl());
        logger.info("[e2e] scenario={} env={}", getClass().getSimpleName(), env);
    }

    @AfterEach
    public void afterEach() {
        for (RobotClient c : opened) {
            try {
                c.close();
            } catch (Exception ignored) { }
        }
        opened.clear();
    }

    /** 申请并启动一个新 robot（连主 WS 端口）；afterEach 自动关闭。 */
    protected RobotClient newRobot() throws InterruptedException {
        return newRobotOnWs(env.wsPort());
    }

    /** 连指定 WS 端口；S04 跨实例场景使用主/从两个 gate 端口。 */
    protected RobotClient newRobotOnWs(int wsPort) throws InterruptedException {
        RobotClient c = RobotClient.builder()
                .host(env.host())
                .wsPort(wsPort)
                .connectTimeout(Duration.ofMillis(env.timeoutMs()))
                .receiveTimeout(Duration.ofMillis(env.timeoutMs()))
                .build();
        c.connect();
        opened.add(c);
        return c;
    }

    /** 连 TCP 端口；S06 多 transport 场景使用。 */
    protected RobotClient newRobotOnTcp(int tcpPort) throws InterruptedException {
        RobotClient c = RobotClient.builder()
                .host(env.host())
                .tcpPort(tcpPort)
                .connectTimeout(Duration.ofMillis(env.timeoutMs()))
                .receiveTimeout(Duration.ofMillis(env.timeoutMs()))
                .build();
        c.connect();
        opened.add(c);
        return c;
    }

    /**
     * 短暂 sleep；用于场景必须等 server 完成异步动作（如 detached 扫描）的场合。
     * 调用方应给出明确语义注释，避免被误读为「重试」。
     */
    protected void sleepQuiet(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("scenario interrupted while sleeping", e);
        }
    }
}
