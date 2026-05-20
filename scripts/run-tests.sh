#!/usr/bin/env bash
# run-tests.sh - GateDemo 一键测试入口（unit + perf + e2e）
#
# 用法：
#   scripts/run-tests.sh                 # 跑全部三段
#   scripts/run-tests.sh --unit-only     # 仅单测 + 覆盖率
#   scripts/run-tests.sh --perf-only     # 仅 JMH
#   scripts/run-tests.sh --e2e-only      # 仅 e2e（默认会用 docker-compose 起服务）
#   scripts/run-tests.sh --no-e2e        # unit + perf 不跑 e2e
#   scripts/run-tests.sh --quick         # 单测跳过慢测 + perf 用极简迭代
#   scripts/run-tests.sh --keep-compose  # e2e 跑完保留 docker-compose 不 down
#
# 报告产物：
#   reports/<ts>/unit/...    surefire/jacoco 原生 HTML
#   reports/<ts>/perf/...    jmh-result.json + jmh-result.txt
#   reports/<ts>/e2e/...     surefire (robot scenarios)
#   reports/<ts>/summary.json 机器可读总结
#   docs/test-reports/<ts>.md Markdown 摘要（便于 git 追溯）
#
# 退出码：
#   0 - 所有阶段都成功
#   1 - 单测失败（fail-fast）
#   2 - 性能测试失败但单测通过
#   3 - e2e 失败但前两段通过
#   4 - 多段失败
#
# 注意：脚本仅在 GateDemo/ 根目录或其父目录执行；自动定位 GateDemo 根。

set -uo pipefail

# ----------------------- 路径定位 -----------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE_DEMO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$GATE_DEMO_ROOT"

# ----------------------- lib -----------------------
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/common.sh"

# ----------------------- 参数解析 -----------------------
RUN_UNIT=1
RUN_PERF=1
RUN_E2E=1
QUICK=0
KEEP_COMPOSE=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --unit-only) RUN_UNIT=1; RUN_PERF=0; RUN_E2E=0 ;;
        --perf-only) RUN_UNIT=0; RUN_PERF=1; RUN_E2E=0 ;;
        --e2e-only)  RUN_UNIT=0; RUN_PERF=0; RUN_E2E=1 ;;
        --no-e2e)    RUN_E2E=0 ;;
        --no-perf)   RUN_PERF=0 ;;
        --no-unit)   RUN_UNIT=0 ;;
        --quick)     QUICK=1 ;;
        --keep-compose) KEEP_COMPOSE=1 ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0 ;;
        *)
            log_err "Unknown argument: $1"; exit 64 ;;
    esac
    shift
done

# ----------------------- 时间戳与输出目录 -----------------------
TS="$(date +%Y-%m-%d-%H%M%S)"
REPORT_ROOT="$GATE_DEMO_ROOT/reports/$TS"
DOC_REPORT_DIR="$GATE_DEMO_ROOT/docs/test-reports"
mkdir -p "$REPORT_ROOT/unit" "$REPORT_ROOT/perf" "$REPORT_ROOT/e2e" "$DOC_REPORT_DIR"

log_info "GateDemo test pipeline: ts=$TS root=$REPORT_ROOT"
log_info "stages: unit=$RUN_UNIT perf=$RUN_PERF e2e=$RUN_E2E quick=$QUICK"

# 各阶段状态（0 success, !=0 fail, -1 skipped）
UNIT_STATUS=-1
PERF_STATUS=-1
E2E_STATUS=-1

# ----------------------- 1) 单测 + JaCoCo -----------------------
if [[ "$RUN_UNIT" == "1" ]]; then
    log_info "=========================================="
    log_info "STAGE 1/3: UNIT TESTS + COVERAGE"
    log_info "=========================================="

    # 清理上次遗留的 surefire-reports，避免被 collect_surefire 误收集
    # （某 module 本次未跑测试时，旧 XML 会以为是本次产物）
    find "$GATE_DEMO_ROOT" -type d -name surefire-reports \
        -not -path '*/node_modules/*' \
        -exec rm -rf {} + 2>/dev/null || true

    UNIT_LOG="$REPORT_ROOT/unit/maven.log"
    UNIT_ARGS=(
        "-pl" ".,common,core,gate-service,login-service,center-service,game-service,player-client"
        "-am"
        "test"
        "-Djacoco.skip=false"
        "-Dsurefire.failIfNoSpecifiedTests=false"
    )
    if [[ "$QUICK" == "1" ]]; then
        UNIT_ARGS+=("-Dtest=!*IntegrationTest")
    fi

    set +e
    ( cd "$GATE_DEMO_ROOT" && mvn "${UNIT_ARGS[@]}" ) | tee "$UNIT_LOG"
    UNIT_STATUS=${PIPESTATUS[0]}
    set -e

    log_info "Collecting unit reports → $REPORT_ROOT/unit/"
    collect_surefire "$GATE_DEMO_ROOT" "$REPORT_ROOT/unit/surefire"
    # JaCoCo verify 阶段才生成 report；如未执行则尝试 mvn verify
    if [[ "$UNIT_STATUS" == "0" ]]; then
        log_info "Running mvn verify -DskipTests to generate jacoco report ..."
        ( cd "$GATE_DEMO_ROOT" && mvn -DskipTests -Djacoco.skip=false verify ) >> "$UNIT_LOG" 2>&1 || true
    fi
    collect_jacoco "$GATE_DEMO_ROOT" "$REPORT_ROOT/unit/jacoco"

    if [[ "$UNIT_STATUS" != "0" ]]; then
        log_err "UNIT stage FAILED (exit=$UNIT_STATUS); will still attempt to write report and skip perf/e2e."
        RUN_PERF=0
        RUN_E2E=0
    else
        log_info "UNIT stage OK."
    fi
fi

# ----------------------- 2) JMH 性能 -----------------------
if [[ "$RUN_PERF" == "1" ]]; then
    log_info "=========================================="
    log_info "STAGE 2/3: JMH BENCHMARKS"
    log_info "=========================================="

    PERF_LOG="$REPORT_ROOT/perf/maven.log"
    JMH_WARMUP=$([[ "$QUICK" == "1" ]] && echo 1 || echo 2)
    JMH_MEASURE=$([[ "$QUICK" == "1" ]] && echo 1 || echo 3)

    set +e
    ( cd "$GATE_DEMO_ROOT" && mvn -pl gate-service -am \
        -DskipTests=false \
        -Djacoco.skip=true \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Pperf \
        -Djmh.warmup="$JMH_WARMUP" \
        -Djmh.measure="$JMH_MEASURE" \
        -Djmh.forks=1 \
        test ) | tee "$PERF_LOG"
    PERF_STATUS=${PIPESTATUS[0]}
    set -e

    if [[ -f "$GATE_DEMO_ROOT/gate-service/target/jmh-result.json" ]]; then
        cp "$GATE_DEMO_ROOT/gate-service/target/jmh-result.json" "$REPORT_ROOT/perf/jmh-result.json"
        log_info "JMH JSON copied → $REPORT_ROOT/perf/jmh-result.json"
    else
        log_warn "JMH JSON not found at gate-service/target/jmh-result.json"
        if [[ "$PERF_STATUS" == "0" ]]; then PERF_STATUS=2; fi
    fi

    if [[ "$PERF_STATUS" == "0" ]]; then
        log_info "PERF stage OK."
    else
        log_err "PERF stage FAILED (exit=$PERF_STATUS); continuing to e2e."
    fi
fi

# ----------------------- 3) e2e robot 场景 -----------------------
if [[ "$RUN_E2E" == "1" ]]; then
    log_info "=========================================="
    log_info "STAGE 3/3: E2E ROBOT SCENARIOS (docker-compose)"
    log_info "=========================================="

    E2E_LOG="$REPORT_ROOT/e2e/maven.log"
    COMPOSE_LOG="$REPORT_ROOT/e2e/docker-compose.log"
    E2E_SERVICES="redis mongodb center-service login-service game-1001 gate-01"

    log_info "Building docker images (if needed) ..."
    ( cd "$GATE_DEMO_ROOT" && docker compose build $E2E_SERVICES ) > "$COMPOSE_LOG" 2>&1
    BUILD_RC=$?
    if [[ "$BUILD_RC" != "0" ]]; then
        log_err "docker compose build failed (rc=$BUILD_RC); see $COMPOSE_LOG"
        E2E_STATUS=$BUILD_RC
    else
        log_info "Starting services (detached) ..."
        ( cd "$GATE_DEMO_ROOT" && docker compose up -d $E2E_SERVICES ) >> "$COMPOSE_LOG" 2>&1
        START_RC=$?
        if [[ "$START_RC" != "0" ]]; then
            log_err "docker compose up failed (rc=$START_RC); see $COMPOSE_LOG"
            E2E_STATUS=$START_RC
        else
            log_info "Waiting for gate-01 health (max 90s) ..."
            if wait_http "http://127.0.0.1:8890/health" 90; then
                log_info "gate-01 healthy; running robot scenarios ..."
                set +e
                ( cd "$GATE_DEMO_ROOT" && \
                    GATE_E2E=true \
                    GATE_E2E_HOST=127.0.0.1 \
                    GATE_E2E_WS_PORT=8888 \
                    GATE_E2E_LOGIN_URL=http://127.0.0.1:9086 \
                    mvn -pl player-client -am \
                        -DskipTests=false \
                        -Djacoco.skip=true \
                        -Dsurefire.failIfNoSpecifiedTests=false \
                        -Dgroups=e2e \
                        -DexcludedGroups= \
                        test ) | tee "$E2E_LOG"
                E2E_STATUS=${PIPESTATUS[0]}
                set -e
            else
                log_err "gate-01 health check timed out; see $COMPOSE_LOG"
                E2E_STATUS=124
            fi

            log_info "Collecting e2e surefire → $REPORT_ROOT/e2e/surefire/"
            collect_surefire "$GATE_DEMO_ROOT/player-client" "$REPORT_ROOT/e2e/surefire"

            if [[ "$KEEP_COMPOSE" != "1" ]]; then
                log_info "Tearing down docker-compose ..."
                ( cd "$GATE_DEMO_ROOT" && docker compose down ) >> "$COMPOSE_LOG" 2>&1 || true
            else
                log_info "--keep-compose set; leaving services running."
            fi
        fi
    fi

    if [[ "$E2E_STATUS" == "0" ]]; then
        log_info "E2E stage OK."
    else
        log_err "E2E stage FAILED (exit=$E2E_STATUS)."
    fi
fi

# ----------------------- 4) 报告生成 -----------------------
log_info "=========================================="
log_info "Generating reports ..."
log_info "=========================================="

if command -v python3 >/dev/null 2>&1; then
    python3 "$SCRIPT_DIR/lib/generate_report.py" \
        --ts "$TS" \
        --report-root "$REPORT_ROOT" \
        --doc-out "$DOC_REPORT_DIR/$TS.md" \
        --unit-status "$UNIT_STATUS" \
        --perf-status "$PERF_STATUS" \
        --e2e-status "$E2E_STATUS"
    REPORT_RC=$?
    if [[ "$REPORT_RC" == "0" ]]; then
        log_info "Markdown summary → $DOC_REPORT_DIR/$TS.md"
        log_info "Machine-readable summary → $REPORT_ROOT/summary.json"
    else
        log_warn "Report generator returned non-zero (rc=$REPORT_RC); summary may be partial."
    fi
else
    log_warn "python3 not found; skipping Markdown summary generation."
fi

# ----------------------- 5) 退出码聚合 -----------------------
FAIL_COUNT=0
[[ "$UNIT_STATUS" != "0" && "$UNIT_STATUS" != "-1" ]] && ((FAIL_COUNT++))
[[ "$PERF_STATUS" != "0" && "$PERF_STATUS" != "-1" ]] && ((FAIL_COUNT++))
[[ "$E2E_STATUS"  != "0" && "$E2E_STATUS"  != "-1" ]] && ((FAIL_COUNT++))

if [[ "$FAIL_COUNT" == "0" ]]; then
    log_info "==== ALL STAGES PASSED ===="
    exit 0
elif [[ "$FAIL_COUNT" == "1" ]]; then
    if [[ "$UNIT_STATUS" != "0" && "$UNIT_STATUS" != "-1" ]]; then exit 1; fi
    if [[ "$PERF_STATUS" != "0" && "$PERF_STATUS" != "-1" ]]; then exit 2; fi
    if [[ "$E2E_STATUS"  != "0" && "$E2E_STATUS"  != "-1" ]]; then exit 3; fi
fi
log_err "==== $FAIL_COUNT STAGE(S) FAILED ===="
exit 4
