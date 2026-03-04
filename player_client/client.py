#!/usr/bin/env python3
"""
Player Client - 模拟玩家客户端

用于测试 Gate 服务的功能
"""

import asyncio
import json
import argparse
import logging
import signal
import time
import websockets

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('player_client')


class PlayerClient:
    """玩家客户端"""
    
    def __init__(self, player_id: int, host: str, port: int):
        self.player_id = player_id
        self.host = host
        self.port = port
        self.uri = f"ws://{host}:{port}"
        self.websocket = None
        self.running = True
        self.seq = 0
        
    async def connect(self):
        """连接到 Gate"""
        try:
            self.websocket = await websockets.connect(self.uri)
            logger.info(f"Connected to {self.uri}")
            
            # 发送认证消息
            auth_msg = {
                "type": "auth",
                "player_id": self.player_id
            }
            await self.websocket.send(json.dumps(auth_msg))
            logger.info(f"Authenticated as player {self.player_id}")
            
            return True
        except Exception as e:
            logger.error(f"Failed to connect: {e}")
            return False
    
    async def send_message(self, msg_type: str, body: dict, game_id: int = 1001):
        """发送游戏消息"""
        self.seq += 1
        msg = {
            "type": "game_msg",
            "game_id": game_id,
            "msg_type": msg_type,
            "seq": self.seq,
            "body": body
        }
        await self.websocket.send(json.dumps(msg))
        logger.info(f"Sent {msg_type}: {body}")
    
    async def send_heartbeat(self):
        """发送心跳"""
        msg = {"type": "heartbeat"}
        await self.websocket.send(json.dumps(msg))
        logger.debug("Heartbeat sent")
    
    async def receive_messages(self):
        """接收消息"""
        try:
            async for message in self.websocket:
                if not self.running:
                    break
                    
                data = json.loads(message)
                msg_type = data.get("msg_type", data.get("type", "unknown"))
                
                if msg_type == "heartbeat_ack":
                    logger.debug("Heartbeat ACK received")
                else:
                    logger.info(f"Received {msg_type}: {data}")
                    
        except websockets.exceptions.ConnectionClosed:
            logger.info("Connection closed")
        except Exception as e:
            logger.error(f"Error receiving messages: {e}")
    
    async def run_test_scenario(self):
        """运行测试场景"""
        logger.info("Starting test scenario...")
        
        # 等待连接稳定
        await asyncio.sleep(1)
        
        # 测试 1: 发送移动请求
        logger.info("Test 1: Send move request")
        await self.send_message("battle.move", {"position": {"x": 100, "y": 200}})
        await asyncio.sleep(2)
        
        # 测试 2: 发送聊天消息
        logger.info("Test 2: Send chat message")
        await self.send_message("chat.send", {"content": "Hello, World!"})
        await asyncio.sleep(2)
        
        # 测试 3: 查询玩家信息
        logger.info("Test 3: Query player info")
        await self.send_message("player.info", {})
        await asyncio.sleep(2)
        
        # 测试 4: 开始战斗
        logger.info("Test 4: Start battle")
        await self.send_message("battle.start", {"battle_id": 999})
        await asyncio.sleep(2)
        
        logger.info("Test scenario completed!")
    
    async def run(self, test_mode: bool = False):
        """运行客户端"""
        if not await self.connect():
            return
        
        # 启动接收任务
        receive_task = asyncio.create_task(self.receive_messages())
        
        if test_mode:
            # 测试模式：运行测试场景后退出
            await self.run_test_scenario()
            self.running = False
        else:
            # 交互模式：定期发送心跳
            try:
                while self.running:
                    await self.send_heartbeat()
                    await asyncio.sleep(30)
            except asyncio.CancelledError:
                pass
        
        # 清理
        receive_task.cancel()
        if self.websocket:
            await self.websocket.close()
        logger.info("Client stopped")
    
    def stop(self):
        """停止客户端"""
        self.running = False


async def main():
    """主入口"""
    parser = argparse.ArgumentParser(description='Player Client')
    parser.add_argument('--player-id', type=int, default=100001, help='Player ID')
    parser.add_argument('--host', type=str, default='localhost', help='Gate host')
    parser.add_argument('--port', type=int, default=8888, help='Gate port')
    parser.add_argument('--test', action='store_true', help='Run in test mode')
    
    args = parser.parse_args()
    
    client = PlayerClient(args.player_id, args.host, args.port)
    
    # 设置信号处理
    def signal_handler():
        logger.info("Shutdown requested")
        client.stop()
    
    loop = asyncio.get_event_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, signal_handler)
    
    await client.run(test_mode=args.test)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Shutdown requested")
