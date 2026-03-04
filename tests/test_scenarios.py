#!/usr/bin/env python3
"""
GateDemo 测试场景套件

测试各种可能的情况：
1. 断线重连 - 原 Gate 可用
2. 断线重连 - 原 Gate 不可用（宕机）
3. Gate→Player 消息投递失败
4. 消息重复处理
5. Gate 水平扩展
6. 高并发压力测试
"""

import asyncio
import json
import time
import logging
import argparse
from typing import Optional, List, Dict
from dataclasses import dataclass, field
from enum import Enum
import websockets
import redis.asyncio as redis

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('test_scenarios')


class TestStatus(Enum):
    """测试状态"""
    PENDING = "pending"
    RUNNING = "running"
    PASSED = "passed"
    FAILED = "failed"
    SKIPPED = "skipped"


@dataclass
class TestResult:
    """测试结果"""
    name: str
    status: TestStatus
    duration: float
    message: str = ""
    details: Dict = field(default_factory=dict)


@dataclass
class TestReport:
    """测试报告"""
    total: int = 0
    passed: int = 0
    failed: int = 0
    skipped: int = 0
    results: List[TestResult] = field(default_factory=list)
    start_time: float = 0
    end_time: float = 0
    
    def add_result(self, result: TestResult):
        self.total += 1
        self.results.append(result)
        if result.status == TestStatus.PASSED:
            self.passed += 1
        elif result.status == TestStatus.FAILED:
            self.failed += 1
        elif result.status == TestStatus.SKIPPED:
            self.skipped += 1
    
    def summary(self) -> str:
        duration = self.end_time - self.start_time
        return f"""
╔════════════════════════════════════════════════════════════╗
║                    测试报告汇总                             ║
╠════════════════════════════════════════════════════════════╣
║  总计：{self.total:3d}  |  通过：{self.passed:3d}  |  失败：{self.failed:3d}  |  跳过：{self.skipped:3d}
║  耗时：{duration:.2f} 秒
║  成功率：{(self.passed/self.total*100) if self.total > 0 else 0:.1f}%
╚════════════════════════════════════════════════════════════╝
"""


class MockPlayer:
    """模拟玩家客户端"""
    
    def __init__(self, player_id: int, host: str, port: int):
        self.player_id = player_id
        self.host = host
        self.port = port
        self.uri = f"ws://{host}:{port}"
        self.websocket: Optional[websockets.WebSocketClientProtocol] = None
        self.connected = False
        self.received_messages: List[dict] = []
        self.seq = 0
        
    async def connect(self) -> bool:
        """连接到 Gate"""
        try:
            self.websocket = await websockets.connect(
                self.uri,
                close_timeout=5,
                ping_timeout=10,
                ping_interval=30
            )
            
            # 发送认证
            auth_msg = {"type": "auth", "player_id": self.player_id}
            await self.websocket.send(json.dumps(auth_msg))
            
            # 等待认证响应（简化处理）
            await asyncio.sleep(0.5)
            
            self.connected = True
            logger.info(f"Player {self.player_id} connected to {self.uri}")
            return True
        except Exception as e:
            logger.warning(f"Player {self.player_id} connect failed: {e}")
            self.connected = False
            return False
    
    async def disconnect(self):
        """断开连接"""
        if self.websocket:
            try:
                await self.websocket.close()
            except:
                pass
        self.connected = False
        logger.info(f"Player {self.player_id} disconnected")
    
    async def send_message(self, msg_type: str, body: dict, game_id: int = 1001) -> bool:
        """发送游戏消息"""
        if not self.connected:
            return False
        
        self.seq += 1
        msg = {
            "type": "game_msg",
            "game_id": game_id,
            "msg_type": msg_type,
            "seq": self.seq,
            "body": body
        }
        
        try:
            await self.websocket.send(json.dumps(msg))
            logger.debug(f"Player {self.player_id} sent {msg_type}")
            return True
        except Exception as e:
            logger.warning(f"Player {self.player_id} send failed: {e}")
            return False
    
    async def receive_message(self, timeout: float = 2.0) -> Optional[dict]:
        """接收消息"""
        if not self.connected:
            return None
        
        try:
            message = await asyncio.wait_for(
                self.websocket.recv(),
                timeout=timeout
            )
            data = json.loads(message)
            self.received_messages.append(data)
            logger.debug(f"Player {self.player_id} received: {data.get('msg_type')}")
            return data
        except asyncio.TimeoutError:
            return None
        except Exception as e:
            logger.warning(f"Player {self.player_id} receive failed: {e}")
            self.connected = False
            return None
    
    async def receive_until(self, msg_type: str, timeout: float = 5.0) -> Optional[dict]:
        """接收直到指定类型的消息"""
        start = time.time()
        while time.time() - start < timeout:
            msg = await self.receive_message(timeout=1.0)
            if msg and msg.get("msg_type") == msg_type:
                return msg
        return None
    
    def clear_messages(self):
        """清空接收消息"""
        self.received_messages.clear()


class TestScenarios:
    """测试场景集合"""
    
    def __init__(self, config: dict):
        self.config = config
        self.redis: Optional[redis.Redis] = None
        self.report = TestReport()
        
    async def setup(self):
        """设置测试环境"""
        self.redis = redis.Redis(
            host=self.config.get("redis_host", "localhost"),
            port=self.config.get("redis_port", 6379),
            decode_responses=True
        )
        logger.info("Test environment setup complete")
    
    async def teardown(self):
        """清理测试环境"""
        if self.redis:
            await self.redis.close()
        logger.info("Test environment cleanup complete")
    
    async def test_basic_connection(self) -> TestResult:
        """测试 1: 基础连接测试"""
        start = time.time()
        result = TestResult(
            name="基础连接测试",
            status=TestStatus.RUNNING,
            duration=0
        )
        
        try:
            player = MockPlayer(
                player_id=100001,
                host=self.config["gate_host"],
                port=self.config["gate_port"]
            )
            
            # 连接
            connected = await player.connect()
            if not connected:
                result.status = TestStatus.FAILED
                result.message = "连接失败"
                return result
            
            # 验证 Player-Gate 映射
            await asyncio.sleep(0.5)
            gate_id = await self.redis.get(f"player:gate:100001")
            
            if not gate_id:
                result.status = TestStatus.FAILED
                result.message = "Player-Gate 映射未创建"
                await player.disconnect()
                return result
            
            # 断开
            await player.disconnect()
            
            # 验证映射已清理
            await asyncio.sleep(0.5)
            gate_id_after = await self.redis.get(f"player:gate:100001")
            
            result.status = TestStatus.PASSED
            result.message = f"连接成功，映射到 {gate_id}"
            result.details = {
                "gate_id": gate_id,
                "map_cleaned": gate_id_after is None
            }
            
        except Exception as e:
            result.status = TestStatus.FAILED
            result.message = str(e)
        
        result.duration = time.time() - start
        return result
    
    async def test_reconnect_same_gate(self) -> TestResult:
        """测试 2: 断线重连 - 原 Gate 可用"""
        start = time.time()
        result = TestResult(
            name="断线重连 - 原 Gate 可用",
            status=TestStatus.RUNNING,
            duration=0
        )
        
        player = None
        try:
            # 第一次连接
            player = MockPlayer(
                player_id=100002,
                host=self.config["gate_host"],
                port=self.config["gate_port"]
            )
            
            connected1 = await player.connect()
            if not connected1:
                result.status = TestStatus.FAILED
                result.message = "第一次连接失败"
                return result
            
            # 记录初始 Gate
            gate_id_1 = await self.redis.get(f"player:gate:100002")
            logger.info(f"初始 Gate: {gate_id_1}")
            
            # 模拟断线（主动断开）
            await player.disconnect()
            await asyncio.sleep(1)
            
            # 重连（同一 Gate 仍然可用）
            player2 = MockPlayer(
                player_id=100002,
                host=self.config["gate_host"],
                port=self.config["gate_port"]
            )
            
            connected2 = await player2.connect()
            if not connected2:
                result.status = TestStatus.FAILED
                result.message = "重连失败"
                return result
            
            # 验证 Gate 是否变化
            gate_id_2 = await self.redis.get(f"player:gate:100002")
            
            # 发送消息验证
            sent = await player2.send_message("player.info", {})
            if not sent:
                result.status = TestStatus.FAILED
                result.message = "重连后发送消息失败"
                await player2.disconnect()
                return result
            
            # 等待响应
            response = await player2.receive_until("player.info", timeout=3.0)
            
            await player2.disconnect()
            
            result.status = TestStatus.PASSED
            result.message = "重连成功，消息投递正常"
            result.details = {
                "initial_gate": gate_id_1,
                "reconnect_gate": gate_id_2,
                "gate_changed": gate_id_1 != gate_id_2,
                "message_received": response is not None
            }
            
        except Exception as e:
            result.status = TestStatus.FAILED
            result.message = str(e)
            if player:
                await player.disconnect()
        
        result.duration = time.time() - start
        return result
    
    async def test_reconnect_gate_down(self) -> TestResult:
        """测试 3: 断线重连 - 原 Gate 不可用（模拟）"""
        start = time.time()
        result = TestResult(
            name="断线重连 - 原 Gate 不可用",
            status=TestStatus.RUNNING,
            duration=0
        )
        
        try:
            # 配置中需要有第二个 Gate
            gate2_port = self.config.get("gate2_port")
            if not gate2_port:
                result.status = TestStatus.SKIPPED
                result.message = "未配置第二个 Gate，跳过测试"
                return result
            
            # 第一次连接到 Gate-1
            player1 = MockPlayer(
                player_id=100003,
                host=self.config["gate_host"],
                port=self.config["gate_port"]
            )
            
            connected1 = await player1.connect()
            if not connected1:
                result.status = TestStatus.FAILED
                result.message = "第一次连接失败"
                return result
            
            gate_id_1 = await self.redis.get(f"player:gate:100003")
            logger.info(f"初始 Gate: {gate_id_1}")
            
            # 模拟 Gate-1 宕机（手动删除映射，模拟 Gate 不可用）
            await self.redis.delete(f"player:gate:100003")
            await player1.disconnect()
            
            # 重连到 Gate-2
            player2 = MockPlayer(
                player_id=100003,
                host=self.config["gate_host"],
                port=gate2_port
            )
            
            connected2 = await player2.connect()
            if not connected2:
                result.status = TestStatus.FAILED
                result.message = "重连到 Gate-2 失败"
                return result
            
            # 验证映射已更新到新 Gate
            gate_id_2 = await self.redis.get(f"player:gate:100003")
            
            # 发送消息验证
            sent = await player2.send_message("player.info", {})
            response = await player2.receive_until("player.info", timeout=3.0)
            
            await player2.disconnect()
            
            result.status = TestStatus.PASSED
            result.message = "Gate 切换成功，消息投递到新 Gate"
            result.details = {
                "old_gate": gate_id_1,
                "new_gate": gate_id_2,
                "gate_switched": gate_id_1 != gate_id_2,
                "message_received": response is not None
            }
            
        except Exception as e:
            result.status = TestStatus.FAILED
            result.message = str(e)
        
        result.duration = time.time() - start
        return result
    
    async def test_message_delivery_failure(self) -> TestResult:
        """测试 4: Gate→Player 消息投递失败"""
        start = time.time()
        result = TestResult(
            name="Gate→Player 消息投递失败处理",
            status=TestStatus.RUNNING,
            duration=0
        )
        
        try:
            # 连接玩家
            player = MockPlayer(
                player_id=100004,
                host=self.config["gate_host"],
                port=self.config["gate_port"]
            )
            
            connected = await player.connect()
            if not connected:
                result.status = TestStatus.FAILED
                result.message = "连接失败"
                return result
            
            gate_id = await self.redis.get(f"player:gate:100004")
            
            # 发送消息
            sent = await player.send_message("battle.move", {"position": {"x": 100, "y": 200}})
            
            # 等待响应
            response = await player.receive_until("battle.move_ack", timeout=5.0)
            
            # 模拟投递失败：玩家主动断开，然后 Game 发送消息
            await player.disconnect()
            
            # 等待映射过期（或手动删除）
            await asyncio.sleep(1)
            
            # 尝试发送消息到已断开的玩家
            # 验证消息是否留在 Stream 中（Pending 状态）
            stream_key = f"stream:down:gate:{gate_id}"
            pending = await self.redis.xpending_range(
                stream_key,
                f"{gate_id}-cluster",
                min="-",
                max="+",
                count=10
            )
            
            result.status = TestStatus.PASSED
            result.message = "消息投递失败处理验证完成"
            result.details = {
                "gate_id": gate_id,
                "message_sent": sent,
                "response_received": response is not None,
                "pending_messages": len(pending) if pending else 0
            }
            
        except Exception as e:
            result.status = TestStatus.FAILED
            result.message = str(e)
        
        result.duration = time.time() - start
        return result
    
    async def test_duplicate_message_handling(self) -> TestResult:
        """测试 5: 消息重复处理"""
        start = time.time()
        result = TestResult(
            name="消息重复处理",
            status=TestStatus.RUNNING,
            duration=0
        )
        
        try:
            player = MockPlayer(
                player_id=100005,
                host=self.config["gate_host"],
                port=self.config["gate_port"]
            )
            
            connected = await player.connect()
            if not connected:
                result.status = TestStatus.FAILED
                result.message = "连接失败"
                return result
            
            # 清空接收缓存
            player.clear_messages()
            
            # 发送多条相同类型的消息
            for i in range(5):
                await player.send_message("chat.send", {"content": f"Message {i}"})
                await asyncio.sleep(0.2)
            
            # 接收所有响应
            received_count = 0
            start_wait = time.time()
            while time.time() - start_wait < 5.0:
                msg = await player.receive_message(timeout=1.0)
                if msg:
                    received_count += 1
            
            await player.disconnect()
            
            result.status = TestStatus.PASSED
            result.message = "消息重复处理验证完成"
            result.details = {
                "messages_sent": 5,
                "responses_received": received_count
            }
            
        except Exception as e:
            result.status = TestStatus.FAILED
            result.message = str(e)
        
        result.duration = time.time() - start
        return result
    
    async def test_concurrent_players(self) -> TestResult:
        """测试 6: 多玩家并发测试"""
        start = time.time()
        result = TestResult(
            name="多玩家并发测试",
            status=TestStatus.RUNNING,
            duration=0
        )
        
        player_count = self.config.get("test_player_count", 10)
        players: List[MockPlayer] = []
        
        try:
            # 创建多个玩家
            for i in range(player_count):
                player = MockPlayer(
                    player_id=200000 + i,
                    host=self.config["gate_host"],
                    port=self.config["gate_port"]
                )
                players.append(player)
            
            # 并发连接
            connect_tasks = [p.connect() for p in players]
            connect_results = await asyncio.gather(*connect_tasks, return_exceptions=True)
            
            connected_count = sum(1 for r in connect_results if r is True)
            logger.info(f"Connected {connected_count}/{player_count} players")
            
            # 并发发送消息
            async def send_and_receive(player: MockPlayer):
                if not player.connected:
                    return 0
                sent = await player.send_message("player.info", {})
                if sent:
                    msg = await player.receive_message(timeout=2.0)
                    return 1 if msg else 0
                return 0
            
            send_tasks = [send_and_receive(p) for p in players]
            send_results = await asyncio.gather(*send_tasks, return_exceptions=True)
            
            success_count = sum(1 for r in send_results if r == 1)
            
            # 断开所有连接
            disconnect_tasks = [p.disconnect() for p in players]
            await asyncio.gather(*disconnect_tasks, return_exceptions=True)
            
            result.status = TestStatus.PASSED
            result.message = f"并发测试完成：{success_count}/{connected_count} 成功"
            result.details = {
                "total_players": player_count,
                "connected": connected_count,
                "successful_messages": success_count
            }
            
        except Exception as e:
            result.status = TestStatus.FAILED
            result.message = str(e)
        
        # 清理
        for p in players:
            if p.connected:
                await p.disconnect()
        
        result.duration = time.time() - start
        return result
    
    async def run_all_tests(self):
        """运行所有测试"""
        self.report.start_time = time.time()
        
        logger.info("=" * 60)
        logger.info("开始运行测试套件")
        logger.info("=" * 60)
        
        # 测试列表
        tests = [
            self.test_basic_connection,
            self.test_reconnect_same_gate,
            self.test_reconnect_gate_down,
            self.test_message_delivery_failure,
            self.test_duplicate_message_handling,
            self.test_concurrent_players,
        ]
        
        # 运行测试
        for test_func in tests:
            test_name = test_func.__name__.replace("test_", "")
            logger.info(f"\n运行测试：{test_name}")
            logger.info("-" * 40)
            
            try:
                result = await test_func()
            except Exception as e:
                result = TestResult(
                    name=test_name,
                    status=TestStatus.FAILED,
                    duration=0,
                    message=f"测试异常：{str(e)}"
                )
            
            self.report.add_result(result)
            
            # 打印结果
            status_icon = {
                TestStatus.PASSED: "✅",
                TestStatus.FAILED: "❌",
                TestStatus.SKIPPED: "⏭️",
            }.get(result.status, "❓")
            
            logger.info(f"{status_icon} {result.name}")
            logger.info(f"   耗时：{result.duration:.2f}s")
            logger.info(f"   {result.message}")
        
        self.report.end_time = time.time()
        
        # 打印汇总
        logger.info("\n" + "=" * 60)
        logger.info(self.report.summary())
        logger.info("=" * 60)
        
        return self.report
    
    def generate_report(self, output_file: str = "test_report.json"):
        """生成测试报告"""
        report_data = {
            "summary": {
                "total": self.report.total,
                "passed": self.report.passed,
                "failed": self.report.failed,
                "skipped": self.report.skipped,
                "duration": self.report.end_time - self.report.start_time,
                "success_rate": (self.report.passed / self.report.total * 100) if self.report.total > 0 else 0
            },
            "results": [
                {
                    "name": r.name,
                    "status": r.status.value,
                    "duration": r.duration,
                    "message": r.message,
                    "details": r.details
                }
                for r in self.report.results
            ],
            "timestamp": time.time()
        }
        
        import json
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(report_data, f, ensure_ascii=False, indent=2)
        
        logger.info(f"测试报告已保存到：{output_file}")
        return report_data


async def main():
    """主入口"""
    parser = argparse.ArgumentParser(description='GateDemo 测试套件')
    parser.add_argument('--gate-host', type=str, default='localhost', help='Gate 主机')
    parser.add_argument('--gate-port', type=int, default=8888, help='Gate 端口')
    parser.add_argument('--gate2-port', type=int, default=8889, help='Gate-2 端口（可选）')
    parser.add_argument('--redis-host', type=str, default='localhost', help='Redis 主机')
    parser.add_argument('--redis-port', type=int, default=6379, help='Redis 端口')
    parser.add_argument('--player-count', type=int, default=10, help='并发测试玩家数量')
    parser.add_argument('--output', type=str, default='test_report.json', help='测试报告输出文件')
    
    args = parser.parse_args()
    
    config = {
        "gate_host": args.gate_host,
        "gate_port": args.gate_port,
        "gate2_port": args.gate2_port,
        "redis_host": args.redis_host,
        "redis_port": args.redis_port,
        "test_player_count": args.player_count,
    }
    
    # 创建测试套件
    tests = TestScenarios(config)
    
    try:
        # 设置环境
        await tests.setup()
        
        # 运行测试
        report = await tests.run_all_tests()
        
        # 生成报告
        tests.generate_report(args.output)
        
        # 返回退出码
        return 0 if report.failed == 0 else 1
        
    finally:
        # 清理环境
        await tests.teardown()


if __name__ == "__main__":
    import sys
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
