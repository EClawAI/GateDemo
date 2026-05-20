#!/usr/bin/env bash
# common.sh - run-tests.sh 通用工具函数（日志、文件收集、健康等待）。
# 不要直接执行；用 `source` 加载。

# ---------------- 日志 ----------------
_GD_COLOR_GREEN='\033[0;32m'
_GD_COLOR_YELLOW='\033[0;33m'
_GD_COLOR_RED='\033[0;31m'
_GD_COLOR_RESET='\033[0m'

log_info() {
    printf "${_GD_COLOR_GREEN}[INFO ]${_GD_COLOR_RESET} %s\n" "$*" >&2
}

log_warn() {
    printf "${_GD_COLOR_YELLOW}[WARN ]${_GD_COLOR_RESET} %s\n" "$*" >&2
}

log_err() {
    printf "${_GD_COLOR_RED}[ERROR]${_GD_COLOR_RESET} %s\n" "$*" >&2
}

# ---------------- 文件收集 ----------------
# collect_surefire <root> <out_dir>
# 从 root 下的所有 module 收集 target/surefire-reports 到 out_dir/<module>/
collect_surefire() {
    local root="$1"
    local out="$2"
    mkdir -p "$out"
    # 遍历所有 target/surefire-reports 目录
    while IFS= read -r dir; do
        local module
        module="$(basename "$(dirname "$(dirname "$dir")")")"
        mkdir -p "$out/$module"
        cp -R "$dir"/* "$out/$module/" 2>/dev/null || true
    done < <(find "$root" -type d -name surefire-reports -not -path '*/node_modules/*' 2>/dev/null)
}

# collect_jacoco <root> <out_dir>
# 收集 module/target/site/jacoco 到 out_dir/<module>/
collect_jacoco() {
    local root="$1"
    local out="$2"
    mkdir -p "$out"
    while IFS= read -r dir; do
        local module
        # dir = .../<module>/target/site/jacoco
        module="$(basename "$(dirname "$(dirname "$(dirname "$dir")")")")"
        mkdir -p "$out/$module"
        cp -R "$dir"/* "$out/$module/" 2>/dev/null || true
    done < <(find "$root" -type d -path '*/target/site/jacoco' 2>/dev/null)
}

# wait_http <url> <max_seconds>
# 轮询 HTTP GET 直到 2xx 或超时；返回 0 成功，1 超时。
wait_http() {
    local url="$1"
    local max="${2:-60}"
    local i=0
    while (( i < max )); do
        if curl -sf --max-time 2 -o /dev/null "$url"; then
            return 0
        fi
        sleep 1
        ((i++))
    done
    return 1
}
