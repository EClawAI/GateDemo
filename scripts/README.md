# GateDemo Test Pipeline

`scripts/run-tests.sh` 是一站式测试入口，把单元测试、JMH 性能基线、e2e robot 场景串成一条流水线，并自动归档报告。

## 快速开始

```bash
# 在 GateDemo/ 根目录执行：
./scripts/run-tests.sh                 # unit + perf + e2e（推荐）
./scripts/run-tests.sh --no-e2e        # 仅 unit + perf（无需 docker）
./scripts/run-tests.sh --unit-only     # 只跑单测
./scripts/run-tests.sh --perf-only     # 只跑 JMH
./scripts/run-tests.sh --e2e-only      # 只跑 e2e（自动 docker-compose up/down）
./scripts/run-tests.sh --quick         # 单测排除 *IntegrationTest；JMH 极简迭代
./scripts/run-tests.sh --keep-compose  # e2e 跑完保留服务，便于手动调试
```

支持的退出码：

| 码 | 含义 |
|---|---|
| 0 | 所有阶段都成功 |
| 1 | 单测失败 |
| 2 | 仅 perf 失败 |
| 3 | 仅 e2e 失败 |
| 4 | 多阶段失败 |

## 产物布局

每次执行生成时间戳目录，互不覆盖：

```
GateDemo/
├── reports/
│   └── 2026-05-20-145523/
│       ├── unit/
│       │   ├── maven.log
│       │   ├── surefire/<module>/TEST-*.xml + .txt
│       │   └── jacoco/<module>/index.html (+ csv/xml)
│       ├── perf/
│       │   ├── maven.log
│       │   └── jmh-result.json
│       ├── e2e/
│       │   ├── maven.log
│       │   ├── docker-compose.log
│       │   └── surefire/<module>/TEST-*.xml
│       └── summary.json                      # 机器可读总结（CI 用）
└── docs/test-reports/
    └── 2026-05-20-145523.md                 # Markdown 摘要（git 提交追溯）
```

- `reports/` 在 `.gitignore` 中，不入库。
- `docs/test-reports/<ts>.md` **入库**，每次执行后 review、commit 即可作为基线对比。

## 各阶段做了什么

### 1) 单测 `mvn test`
- 全 module 跑 `mvn -pl ...,gate-service,player-client,... test`
- JaCoCo 收集 instruction 覆盖率（成功时再跑一次 `mvn verify -DskipTests` 生成 HTML）
- 失败 → 后续阶段跳过；脚本退出码 1

### 2) JMH `-Pperf`
- 在 `gate-service` 中跑 `*Benchmark` 类
- 入口：`gate-service/src/test/java/.../perf/BenchmarkRunner.java`
- 当前覆盖（23 个 case）：
  - `FlowMetricsBenchmark`：takeover / resume / replay / latency 8 case
  - `MessageCodecBenchmark`：encode/decode × 32B/256B/2KB 6 case
  - `DownstreamBufferBenchmark`：enqueue/ack/drain × capacity {64,256,1024} 12 case（4 × 3 param）
  - `FlowSessionManagerBenchmark`：newFlow / newFlow_topout / resume_same_gw / resume_cross_gw 4 case（in-process，含 stub Redis）
  - `RouteRegistryBenchmark`：getIdByName / getByMsgId / getTargetService / getNameById hit/miss 5 case
- 默认 warmup=2 / measurement=3 / fork=1；`--quick` 时降为 1/1/1
- 失败 ≠ 阻断 e2e

### 3) e2e robot 场景
- `docker compose up -d redis mongodb center-service login-service game-1001 gate-01 gate-02`
- 等 `gate-01` 健康（`http://127.0.0.1:8890/health`，最多 90s）+ `gate-02` 健康（`8891/health`，最多 60s，超时不阻断）
- 跑 `mvn -pl player-client -Dgroups=e2e test`，注入 `GATE_E2E_*` 环境变量
- `--keep-compose` 关闭则自动 `docker compose down`
- 当前覆盖（7 个场景）：
  - `S01_AuthFlowTest`：login → AUTH → AuthResponse 校验（smoke）
  - `S02_FlowSessionLifecycleTest`：NEW→RESUME 同 flowId / NEW 顶号 / 伪造 flowId 降级
  - `S03_WeakNetworkResumeTest`：N 个 HeartbeatAck 进 buffer → abruptClose → RESUME `lastClientRecvSeq=0` 强制 replay
  - `S04_CrossInstanceTakeoverTest`：gate-01 NEW → gate-02 RESUME（同 flowId）→ 验证旧 channel 被 Pub/Sub evict
  - `S05_OfflineMessageMergeTest`：NEW→detach→RESUME / cross-evict 路径不阻塞主链路 smoke
  - `S06_MultiTransportTest`：WebSocket 与 TCP 双 transport 并发 AUTH（TCP 端口不可达时自动 skip）
  - `S07_ConcurrencyStressTest`：N 并发 robot × K 心跳，flowId 互不重复且 ack 全收齐

## 报告解析（Markdown 摘要）

`scripts/lib/generate_report.py` 把三段产物聚合成 Markdown：

- **阶段汇总** 表：每段 PASS/FAIL/SKIPPED + 关键计数
- **单测明细**：按 module 拆分 + 失败用例 top 20
- **覆盖率**：整体 + 每 module instruction coverage
- **JMH 基线**：每条 benchmark 的 score / error / unit
- **e2e 明细**：场景统计 + 失败原因

> 不调用任何大模型 / LLM；纯本地解析。

## 新增测试

### 加单元测试
在对应 module 的 `src/test/java/.../` 下新增 `*Test.java`，正常的 JUnit5 即可，无需改脚本。

### 加 JMH benchmark
在 `gate-service/src/test/java/com/clawai/gatedemo/gate/perf/` 下新建 `XxxBenchmark.java`，类名后缀 **必须是 `Benchmark`**（被 `BenchmarkRunner.include(".*Benchmark$")` 自动发现）。

示例：

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class FooBenchmark {
    @Benchmark
    public void bar(Blackhole bh) {
        bh.consume(...);
    }
}
```

### 加 e2e 场景
在 `player-client/src/test/java/com/clawai/gatedemo/client/robot/scenarios/` 新建 `Sxx_*Test.java`，继承 `AbstractRobotScenario`：

```java
@DisplayName("Sxx: 你的场景")
public class Sxx_YourTest extends AbstractRobotScenario {
    @Test
    void someFlow() throws Exception {
        LoginClient.LoginResult lr = login.login(290001L);
        try (RobotClient r = newRobot()) {                       // 默认 WS：env.wsPort()
            AuthResponse resp = r.auth(lr.token(), lr.gameId());
            assertTrue(resp.getSuccess());
        }
        // 跨实例：try (RobotClient r2 = newRobotOnWs(env.wsPort2())) {...}
        // TCP：    try (RobotClient r3 = newRobotOnTcp(env.tcpPort())) {...}
    }
}
```

继承 `AbstractRobotScenario` 后会自动：
- 带 `@Tag("e2e")`，被 `-Dgroups=e2e` 包含；普通 `mvn test` 自动跳过（player-client pom 默认 `excludedGroups=e2e`）
- `@AfterEach` 关闭所有 `newRobot*()` 创建的 client
- 提供 `env` / `login` / `newRobot()` / `newRobotOnWs(p)` / `newRobotOnTcp(p)` / `sleepQuiet(d)` 工厂

### 环境变量

scripts/run-tests.sh 默认注入，可在 CI 或本地手动覆盖：

| 变量 | 默认 | 用途 |
|---|---|---|
| `GATE_E2E_HOST` | 127.0.0.1 | gate 实例地址 |
| `GATE_E2E_WS_PORT` | 8888 | 主 WS（gate-01） |
| `GATE_E2E_WS_PORT_2` | 8889 | 第二 WS（gate-02），S04 用 |
| `GATE_E2E_TCP_PORT` | 9999 | TCP（gate-01），S06 用；置 0 关闭 |
| `GATE_E2E_LOGIN_URL` | http://127.0.0.1:9086 | login-service base URL |
| `GATE_E2E_GAME_ID` | 1001 | 默认 gameId |
| `GATE_E2E_TIMEOUT_MS` | 10000 | 单次 IO 超时 |
| `GATE_E2E_DETACHED_TTL_SECONDS` | 60 | 与 gate 配置对齐，S02/S05 用于判定过期 |
| `GATE_E2E_STRESS_N` | 10 | S07 并发 robot 数 |
| `GATE_E2E_STRESS_K` | 5 | S07 每 robot 心跳数 |
| `GATE_E2E_STRESS_MAX_MS` | 30000 | S07 总耗时上限 |

## 前置依赖

- JDK 21+（与 GateDemo 主项目一致）
- Maven 3.8+
- Docker + Docker Compose（仅 e2e 阶段需要）
- Python 3.8+（报告生成；可选，缺失时跳过 Markdown 摘要）
- `curl`（健康检查）

## 故障排查

- **`mvn ... No tests matching pattern`**：通过加 `-Dsurefire.failIfNoSpecifiedTests=false`（脚本默认已加）。
- **JaCoCo Unsupported class file major version 67**：使用 JDK 23 会报；脚本在 perf/e2e 段已默认 `-Djacoco.skip=true`。单测段若遇到，可手动在 `pom.xml` 升 jacoco 版本，或临时改脚本默认 skip。
- **docker compose: command not found**：你的环境是旧版本 `docker-compose`（带横线）；修改 `run-tests.sh` 中 `docker compose` → `docker-compose`，或升级 Docker。
- **`gate-01 health check timed out`**：手动 `docker compose logs gate-01` 查看启动错误；最常见是 Redis 密码或 JWT_SECRET 未对齐。
- **e2e 场景全部 SKIPPED**：检查脚本里 `GATE_E2E=true` 是否传入 mvn 子进程；本地手动跑可显式 `GATE_E2E=true mvn -pl player-client test`。
