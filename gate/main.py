#!/usr/bin/env python3
"""
Gate Service - 无状态游戏网关服务

职责:
1. 管理玩家 TCP/WebSocket 连接
2. 维护 Player-Gate 映射（Redis）
3. 消费下行 Stream（stream:down:gate:{gate_id}）
4. 转发上行消息到 Game Stream（stream:up:game:{game_id}）
"""

import asyncio
import json
import os
import signal
import sys
import time
import logging
from typing import Dict, Optional
import redis.asyncio as redis
import websockets
from websockets.server import WebSocketServerProtocol

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('gate')

# 从环境变量读取配置
GATE_ID = os.getenv('GATE_ID', 'gate-01')
GATE_HOST = os.getenv('GATE_HOST', '0.0.0.0')
GATE_PORT = int(os.getenv('GATE_PORT', '8888'))
REDIS_HOST = os.getenv('REDIS_HOST', 'localhost')
REDIS_PORT = int(os.getenv('REDIS_PORT', '6379'))
HEARTBEAT_INTERVAL = int(os.getenv('HEARTBEAT_INTERVAL', '60'))
MAP_TTL = int(os.getenv('MAP_TTL', '300'))


class GateService:
    """Gate 服务主类"""
    
    def __init__(self):
        self.gate_id = GATE_ID
        self.redis: Optional[redis.Redis] = None
        self.players: Dict[int, WebSocketServerProtocol] = {}  # player_id -> websocket
        self.running = True
        self.stream_key = f"stream:down:gate:{self.gate_id}"
        self.consumer_group = f"{self.gate_id}-cluster"
        self.consumer_name = f"{self.gate_id}-{os.getpid()}"
        
    async def connect_redis(self):
        """连接 Redis"""
        self.redis = redis.Redis(
            host=REDIS_HOST,
            port=REDIS_PORT,
            decode_responses=True
        )
        logger.info(f"Connected to Redis at {REDIS_HOST}:{REDIS_PORT}")
        
        # 创建 Consumer Group（如果不存在）
        try:
            await self.redis.xgroup_create(
                self.stream_key,
                self.consumer_group,
                id="0",
                mkstream=True
            )
            logger.info(f"Created consumer group: {self.consumer_group}")
        except redis.exceptions.ResponseError as e:
            if "BUSYGROUP" in str(e):
                logger.info(f"Consumer group already exists: {self.consumer_group}")
            else:
                raise
    
    async def register_player(self, player_id: int):
        """注册玩家到 Redis 映射表"""
        key = f"player:gate:{player_id}"
        await self.redis.set(key, self.gate_id, ex=MAP_TTL)
        logger.info(f"Player {player_id} registered to {self.gate_id}")
    
    async def unregister_player(self, player_id: int):
        """取消玩家注册"""
        key = f"player:gate:{player_id}"
        await self.redis.delete(key)
        if player_id in self.players:
            del self.players[player_id]
        logger.info(f"Player {player_id} unregistered from {self.gate_id}")
    
    async def heartbeat_renew(self, player_id: int):
        """心跳续期"""
        while player_id in self.players:
            await asyncio.sleep(HEARTBEAT_INTERVAL)
            if player_id in self.players:
                key = f"player:gate:{player_id}"
                await self.redis.expire(key, MAP_TTL)
                logger.debug(f"Player {player_id} heartbeat renewed")
    
    async def send_to_player(self, player_id: int, message: dict):
        """发送消息给玩家"""
        if player_id in self.players:
            try:
                ws = self.players[player_id]
                await ws.send(json.dumps(message))
                logger.debug(f"Message sent to player {player_id}: {message.get('msg_type')}")
                return True
            except Exception as e:
                logger.error(f"Failed to send to player {player_id}: {e}")
                return False
        else:
            logger.warning(f"Player {player_id} not connected to this gate")
            return False
    
    async def forward_to_game(self, player_id: int, game_id: int, message: dict):
        """转发玩家消息到 Game Stream"""
        stream_key = f"stream:up:game:{game_id}"
        await self.redis.xadd(stream_key, {
            "gate_id": self.gate_id,
            "player_id": str(player_id),
            "msg_type": message.get("msg_type", "unknown"),
            "seq": str(message.get("seq", 0)),
            "timestamp": str(int(time.time() * 1000)),
            "body": json.dumps(message.get("body", {}))
        })
        logger.debug(f"Message forwarded to game {game_id} from player {player_id}")
    
    async def consume_downstream(self):
        """消费下行 Stream"""
        logger.info(f"Starting to consume downstream: {self.stream_key}")
        
        while self.running:
            try:
                # 读取消息
                messages = await self.redis.xreadgroup(
                    groupname=self.consumer_group,
                    consumername=self.consumer_name,
                    streams={self.stream_key: ">"},
                    count=100,
                    block=5000
                )
                
                if not messages:
                    continue
                
                for stream, msgs in messages:
                    for msg_id, fields in msgs:
                        await self.process_downstream_message(msg_id, fields)
                        
            except redis.exceptions.ConnectionError as e:
                logger.error(f"Redis connection error: {e}")
                await asyncio.sleep(1)
                await self.connect_redis()
            except Exception as e:
                logger.error(f"Error consuming downstream: {e}")
                await asyncio.sleep(1)
    
    async def process_downstream_message(self, msg_id: str, fields: dict):
        """处理下行消息"""
        try:
            player_id = int(fields.get("player_id", 0))
            msg_type = fields.get("msg_type", "unknown")
            body = json.loads(fields.get("body", "{}"))
            
            # 构建消息
            message = {
                "seq": int(fields.get("seq", 0)),
                "msg_type": msg_type,
                "body": body,
                "timestamp": int(fields.get("timestamp", 0))
            }
            
            # 发送给玩家
            success = await self.send_to_player(player_id, message)
            
            if success:
                # 等待玩家 ACK（简化处理，立即 ACK）
                await asyncio.sleep(0.1)
                # 确认消费
                await self.redis.xack(self.stream_key, self.consumer_group, msg_id)
                logger.debug(f"Message {msg_id} acked for player {player_id}")
            else:
                # 发送失败，不 ACK，让其他 Gate 或重连后消费
                logger.warning(f"Message {msg_id} not acked for player {player_id}")
                
        except Exception as e:
            logger.error(f"Error processing message {msg_id}: {e}")
    
    async def handle_player_connection(self, websocket: WebSocketServerProtocol, path: str):
        """处理玩家连接"""
        player_id = None
        try:
            # 等待玩家认证
            auth_msg = await websocket.recv()
            auth_data = json.loads(auth_msg)
            
            if auth_data.get("type") != "auth":
                await websocket.close(1008, "Auth required")
                return
            
            player_id = int(auth_data.get("player_id", 0))
            if player_id <= 0:
                await websocket.close(1008, "Invalid player_id")
                return
            
            # 注册玩家
            self.players[player_id] = websocket
            await self.register_player(player_id)
            
            # 启动心跳续期
            heartbeat_task = asyncio.create_task(self.heartbeat_renew(player_id))
            
            logger.info(f"Player {player_id} connected to {self.gate_id}")
            
            # 处理玩家消息
            async for message in websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get("type", "")
                    
                    if msg_type == "heartbeat":
                        # 心跳响应
                        await websocket.send(json.dumps({"type": "heartbeat_ack"}))
                    elif msg_type == "game_msg":
                        # 转发到 Game
                        game_id = data.get("game_id", 1001)
                        await self.forward_to_game(player_id, game_id, data)
                    else:
                        logger.warning(f"Unknown message type: {msg_type}")
                        
                except json.JSONDecodeError:
                    logger.warning(f"Invalid JSON from player {player_id}")
                    
        except websockets.exceptions.ConnectionClosed:
            logger.info(f"Player {player_id} connection closed")
        except Exception as e:
            logger.error(f"Error handling player {player_id}: {e}")
        finally:
            # 清理
            if player_id:
                heartbeat_task.cancel()
                await self.unregister_player(player_id)
    
    async def run(self):
        """运行 Gate 服务"""
        # 连接 Redis
        await self.connect_redis()
        
        # 启动下行消费者
        consume_task = asyncio.create_task(self.consume_downstream())
        
        # 启动 WebSocket 服务器
        logger.info(f"Starting WebSocket server on {GATE_HOST}:{GATE_PORT}")
        async with websockets.serve(
            self.handle_player_connection,
            GATE_HOST,
            GATE_PORT
        ) as server:
            logger.info(f"Gate {self.gate_id} is running")
            
            # 等待关闭信号
            stop_event = asyncio.Event()
            
            def signal_handler():
                logger.info("Received stop signal")
                self.running = False
                stop_event.set()
            
            loop = asyncio.get_event_loop()
            for sig in (signal.SIGTERM, signal.SIGINT):
                loop.add_signal_handler(sig, signal_handler)
            
            await stop_event.wait()
        
        # 清理
        consume_task.cancel()
        await self.redis.close()
        logger.info("Gate service stopped")


async def main():
    """主入口"""
    gate = GateService()
    await gate.run()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Shutdown requested")
