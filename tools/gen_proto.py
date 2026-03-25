#!/usr/bin/env python3
"""
Proto 消息注册表生成器。

扫描 proto/ 目录下所有 .proto 文件，提取 message 定义及 route_to option，
使用 CRC32 哈希消息名生成 32 位消息 ID，校验后输出 message_registry.json。
"""

import binascii
import json
import os
import re
import sys
from datetime import datetime, timezone

PROTO_DIR = os.path.join(os.path.dirname(__file__), '..', 'proto')
OUTPUT_PATH = os.path.join(os.path.dirname(__file__), '..', 'common', 'config', 'message_registry.json')

MESSAGE_BLOCK_RE = re.compile(
    r'^message\s+(\w+)\s*\{(.*?)\n\}',
    re.MULTILINE | re.DOTALL
)

ROUTE_TO_RE = re.compile(
    r'option\s+\(gateoptions\.route_to\)\s*=\s*"([^"]*)"\s*;'
)


def crc32_hash(name: str) -> int:
    """对消息名做 CRC32 哈希，返回 32 位无符号整数。"""
    return binascii.crc32(name.encode('utf-8')) & 0xFFFFFFFF


def parse_proto_file(filepath: str) -> list[dict]:
    """解析单个 proto 文件，返回消息列表。"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    filename = os.path.basename(filepath)
    messages = []

    for match in MESSAGE_BLOCK_RE.finditer(content):
        name = match.group(1)
        body = match.group(2)

        route_match = ROUTE_TO_RE.search(body)
        if route_match:
            route_to = route_match.group(1)
            direction = 'upstream'
            service = route_to if route_to else None
        else:
            direction = 'downstream'
            service = None

        messages.append({
            'name': name,
            'service': service,
            'direction': direction,
            'file': filename,
        })

    return messages


def validate_upstream_route(messages: list[dict]) -> list[str]:
    """校验上行消息必须有非空 route_to。"""
    errors = []
    for msg in messages:
        if msg['direction'] == 'upstream' and not msg['service']:
            errors.append(
                f"  上行消息 '{msg['name']}' (文件: {msg['file']}) 的 route_to 为空"
            )
    return errors


def check_id_collision(messages: list[dict]) -> list[str]:
    """检查消息 ID 碰撞。"""
    errors = []
    seen: dict[int, str] = {}
    for msg in messages:
        msg_id = msg['id']
        if msg_id in seen:
            errors.append(
                f"  ID 碰撞: '{msg['name']}' 与 '{seen[msg_id]}' "
                f"的 CRC32 ID 相同 ({msg_id})，请修改其中一个消息名"
            )
        else:
            seen[msg_id] = msg['name']
    return errors


def main():
    proto_dir = os.path.normpath(PROTO_DIR)
    if not os.path.isdir(proto_dir):
        print(f"错误: proto 目录不存在: {proto_dir}", file=sys.stderr)
        sys.exit(1)

    proto_files = sorted([
        f for f in os.listdir(proto_dir) if f.endswith('.proto')
    ])

    if not proto_files:
        print(f"错误: proto 目录中没有 .proto 文件: {proto_dir}", file=sys.stderr)
        sys.exit(1)

    all_messages: list[dict] = []
    for pf in proto_files:
        filepath = os.path.join(proto_dir, pf)
        all_messages.extend(parse_proto_file(filepath))

    if not all_messages:
        print("错误: 未找到任何 message 定义", file=sys.stderr)
        sys.exit(1)

    for msg in all_messages:
        msg['id'] = crc32_hash(msg['name'])

    route_errors = validate_upstream_route(all_messages)
    if route_errors:
        print("校验失败 — 上行消息 route_to 错误:", file=sys.stderr)
        for e in route_errors:
            print(e, file=sys.stderr)
        sys.exit(1)

    collision_errors = check_id_collision(all_messages)
    if collision_errors:
        print("校验失败 — 消息 ID 碰撞:", file=sys.stderr)
        for e in collision_errors:
            print(e, file=sys.stderr)
        sys.exit(1)

    registry = {
        'generated_at': datetime.now(timezone.utc).isoformat(),
        'hash_algorithm': 'CRC32',
        'messages': [
            {
                'id': msg['id'],
                'name': msg['name'],
                'service': msg['service'],
                'direction': msg['direction'],
                'file': msg['file'],
            }
            for msg in all_messages
        ]
    }

    output_path = os.path.normpath(OUTPUT_PATH)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(registry, f, indent=2, ensure_ascii=False)

    upstream = [m for m in all_messages if m['direction'] == 'upstream']
    downstream = [m for m in all_messages if m['direction'] == 'downstream']

    print("=" * 50)
    print("  Proto 消息注册表生成完成")
    print("=" * 50)
    print(f"  proto 文件数:     {len(proto_files)}")
    print(f"  消息总数:         {len(all_messages)}")
    print(f"  上行消息:         {len(upstream)}")
    print(f"  下行消息:         {len(downstream)}")
    print(f"  JSON 输出路径:    {output_path}")
    print("=" * 50)


if __name__ == '__main__':
    main()
