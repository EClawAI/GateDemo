#!/bin/bash
# GateDemo 测试运行脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "╔════════════════════════════════════════════════════════════╗"
echo "║                  GateDemo 测试套件                          ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# 检查 Redis 是否运行
check_redis() {
    if ! command -v redis-cli &> /dev/null; then
        echo -e "${YELLOW}警告：redis-cli 未安装，跳过 Redis 检查${NC}"
        return 0
    fi
    
    if ! redis-cli ping &> /dev/null; then
        echo -e "${RED}错误：Redis 未运行${NC}"
        echo "请先启动 Redis:"
        echo "  docker-compose up -d redis"
        echo "  或"
        echo "  redis-server"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Redis 连接正常${NC}"
}

# 检查 Gate 服务是否运行
check_gate() {
    local port=${1:-8888}
    if command -v curl &> /dev/null; then
        if curl -s --connect-timeout 2 http://localhost:$port > /dev/null 2>&1; then
            echo -e "${GREEN}✓ Gate 服务运行在端口 $port${NC}"
            return 0
        fi
    fi
    
    # 尝试用 Python 检查 WebSocket
    python3 -c "
import asyncio
import websockets
async def check():
    try:
        ws = await websockets.connect('ws://localhost:$port', close_timeout=2)
        await ws.close()
        return True
    except:
        return False
result = asyncio.run(check())
exit(0 if result else 1)
" 2>/dev/null && echo -e "${GREEN}✓ Gate 服务运行在端口 $port${NC}" || echo -e "${YELLOW}⚠ Gate 服务未在端口 $port 运行${NC}"
}

# 安装依赖
install_deps() {
    echo "安装 Python 依赖..."
    pip install -q -r requirements.txt
    echo -e "${GREEN}✓ 依赖安装完成${NC}"
}

# 运行测试
run_tests() {
    local gate_port=${1:-8888}
    local gate2_port=${2:-8889}
    local player_count=${3:-10}
    local output=${4:-test_report.json}
    
    echo ""
    echo "运行测试..."
    echo "  Gate 端口：$gate_port"
    echo "  Gate-2 端口：$gate2_port"
    echo "  并发玩家数：$player_count"
    echo "  报告输出：$output"
    echo ""
    
    python3 tests/test_scenarios.py \
        --gate-host localhost \
        --gate-port "$gate_port" \
        --gate2-port "$gate2_port" \
        --redis-host localhost \
        --redis-port 6379 \
        --player-count "$player_count" \
        --output "$output"
    
    local exit_code=$?
    
    echo ""
    if [ $exit_code -eq 0 ]; then
        echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║                    所有测试通过！                           ║${NC}"
        echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
    else
        echo -e "${RED}╔════════════════════════════════════════════════════════════╗${NC}"
        echo -e "${RED}║                    部分测试失败                            ║${NC}"
        echo -e "${RED}╚════════════════════════════════════════════════════════════╝${NC}"
    fi
    
    return $exit_code
}

# 显示测试报告
show_report() {
    local report_file=${1:-test_report.json}
    
    if [ ! -f "$report_file" ]; then
        echo -e "${YELLOW}测试报告文件不存在：$report_file${NC}"
        return 1
    fi
    
    echo ""
    echo "╔════════════════════════════════════════════════════════════╗"
    echo "║                    测试报告详情                            ║"
    echo "╚════════════════════════════════════════════════════════════╝"
    echo ""
    
    python3 -c "
import json
with open('$report_file', 'r', encoding='utf-8') as f:
    data = json.load(f)

summary = data['summary']
print(f\"测试总数：{summary['total']}\")
print(f\"通过：{summary['passed']}\")
print(f\"失败：{summary['failed']}\")
print(f\"跳过：{summary['skipped']}\")
print(f\"成功率：{summary['success_rate']:.1f}%\")
print(f\"耗时：{summary['duration']:.2f}秒\")
print()
print('详细结果:')
print('-' * 60)

for r in data['results']:
    status_icon = {'passed': '✅', 'failed': '❌', 'skipped': '⏭️'}.get(r['status'], '❓')
    print(f\"{status_icon} {r['name']}\")
    print(f\"   耗时：{r['duration']:.2f}s\")
    print(f\"   {r['message']}\")
    if r.get('details'):
        for k, v in r['details'].items():
            print(f\"   - {k}: {v}\")
    print()
"
}

# 快速测试（仅基础测试）
quick_test() {
    echo "运行快速测试（仅基础连接和消息）..."
    python3 tests/test_scenarios.py \
        --gate-host localhost \
        --gate-port 8888 \
        --player-count 3 \
        --output quick_test_report.json
}

# 压力测试
stress_test() {
    local player_count=${1:-100}
    
    echo "运行压力测试（$player_count 并发玩家）..."
    python3 tests/test_scenarios.py \
        --gate-host localhost \
        --gate-port 8888 \
        --player-count "$player_count" \
        --output stress_test_report.json
}

# 主菜单
main() {
    case "${1:-run}" in
        check)
            check_redis
            check_gate 8888
            check_gate 8889
            ;;
        install)
            install_deps
            ;;
        run)
            check_redis
            check_gate 8888
            run_tests 8888 8889 10
            show_report test_report.json
            ;;
        quick)
            check_redis
            quick_test
            show_report quick_test_report.json
            ;;
        stress)
            check_redis
            stress_test "${2:-100}"
            show_report stress_test_report.json
            ;;
        report)
            show_report "${2:-test_report.json}"
            ;;
        help|*)
            echo "用法：$0 {check|install|run|quick|stress|report|help}"
            echo ""
            echo "命令:"
            echo "  check    检查 Redis 和 Gate 服务状态"
            echo "  install  安装 Python 依赖"
            echo "  run      运行完整测试套件"
            echo "  quick    运行快速测试（基础连接和消息）"
            echo "  stress   运行压力测试（默认 100 并发）"
            echo "  report   显示测试报告"
            echo "  help     显示帮助信息"
            echo ""
            echo "示例:"
            echo "  $0 run              # 运行完整测试"
            echo "  $0 quick            # 快速测试"
            echo "  $0 stress 50        # 50 并发压力测试"
            echo "  $0 report           # 查看测试报告"
            ;;
    esac
}

main "$@"
