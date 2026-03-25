#!/usr/bin/env bash
#
# Proto 消息注册表生成 & protobuf 编译一体化脚本。
#
# 用法: sh tools/gen_proto.sh
#
# 流程:
#   1. 检查 Python3 和 Maven 环境
#   2. 调用 gen_proto.py 解析 proto → 生成 message_registry.json
#   3. 调用 mvn -pl common compile 编译 proto 生成 Java 类

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- 环境检查 ----

if ! command -v python3 &>/dev/null; then
    echo "错误: 未找到 python3，请先安装 Python 3" >&2
    exit 1
fi

if ! command -v mvn &>/dev/null; then
    echo "错误: 未找到 mvn，请先安装 Maven" >&2
    exit 1
fi

if [ ! -d "$PROJECT_ROOT/proto" ]; then
    echo "错误: proto 目录不存在: $PROJECT_ROOT/proto" >&2
    exit 1
fi

# ---- 生成消息注册表 ----

echo "[1/2] 生成消息注册表..."
python3 "$SCRIPT_DIR/gen_proto.py"

# ---- 编译 proto ----

echo ""
echo "[2/2] 编译 proto (mvn -pl common compile)..."
cd "$PROJECT_ROOT"
mvn -pl common compile -q

echo ""
echo "全部完成!"
