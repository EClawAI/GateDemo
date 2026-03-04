#!/usr/bin/env python3
"""
Game Service - 游戏逻辑服务

职责:
1. 消费上行 Stream（stream:up:game:{game_id}）
2. 处理玩家游戏逻辑
3. 发送响应消息到下行 Stream（通过 Player-Gate 映射路由）
"""

import asyncio
import json
import os
import signal
import time
import logging
from typing import Optional
import redis.asyncio as redis

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('game')

# 从环境变量读取配置
GAME_ID = os.getenv('GAME_ID', 'game-1001')
REDIS_HOST = os.getenv('REDIS_HOST', 'localhost')
REDIS_PORT = int(os.getenv('REDIS_PORT', '6379'))


class GameService:
    """Game 服务主类"""
    
    def __init__(self):
        self.game_id = GAME_ID
        self.redis: Optional[redis.Redis] = None
        self.running = True
        self.upstream_key = f"stream:up:game:{self.game_id}"
        self.consumer_group = f"{self.game_id}-cluster"
        self.consumer_name = f"{self.game_id}-{os.getpid()}"
        
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
                self.upstream_key,
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
    
    async def get_player_gate(self, player_id: int) -> Optional[str]:
        """查询玩家所在 Gate"""
        key = f"player:gate:{player_id}"
        gate_id = await self.redis.get(key)
        return gate_id
    
    async def send_to_player(self, player_id: int, message: dict) -> bool:
        """发送消息给玩家（通过下行 Stream）"""
        # 查询玩家所在 Gate
        gate_id = await self.get_player_gate(player_id)
        
        if not gate_id:
            logger.warning(f"Player {player_id} not connected to any gate")
            return False
        
        # 写入目标 Gate 的下行 Stream
        stream_key = f"stream:down:gate:{gate_id}"
        
        msg = {
            "player_id": str(player_id),
            "seq": str(message.get("seq", 0)),
            "msg_type": message.get("msg_type", "unknown"),
            "timestamp": str(int(time.time() * 1000)),
            "body": json.dumps(message.get("body", {}))
        }
        
        await self.redis.xadd(stream_key, msg)
        logger.debug(f"Message sent to player {player_id} via gate {gate_id}")
        return True
    
    async def consume_upstream(self):
        """消费上行 Stream"""
        logger.info(f"Starting to consume upstream: {self.upstream_key}")
        
        while self.running:
            try:
                # 读取消息
                messages = await self.redis.xreadgroup(
                    groupname=self.consumer_group,
                    consumername=self.consumer_name,
                    streams={self.upstream_key: ">"},
                    count=100,
                    block=5000
                )
                
                if not messages:
                    continue
                
                for stream, msgs in messages:
                    for msg_id, fields in msgs:
                        await self.process_upstream_message(msg_id, fields)
                        
            except redis.exceptions.ConnectionError as e:
                logger.error(f"Redis connection error: {e}")
                await asyncio.sleep(1)
                await self.connect_redis()
            except Exception as e:
                logger.error(f"Error consuming upstream: {e}")
                await asyncio.sleep(1)
    
    async def process_upstream_message(self, msg_id: str, fields: dict):
        """处理上行消息"""
        try:
            player_id = int(fields.get("player_id", 0))
            gate_id = fields.get("gate_id", "unknown")
            msg_type = fields.get("msg_type", "unknown")
            body = json.loads(fields.get("body", "{}"))
            seq = int(fields.get("seq", 0))
            
            logger.info(f"Processing {msg_type} from player {player_id} (via {gate_id})")
            
            # 根据消息类型处理游戏逻辑
            response = await self.handle_game_message(player_id, msg_type, body)
            
            if response:
                # 发送响应给玩家
                response["seq"] = seq + 1  # 响应序列号 +1
                await self.send_to_player(player_id, response)
            
            # 确认消费
            await self.redis.xack(self.upstream_key, self.consumer_group, msg_id)
            logger.debug(f"Message {msg_id} acked")
            
        except Exception as e:
            logger.error(f"Error processing message {msg_id}: {e}")
    
    async def handle_game_message(self, player_id: int, msg_type: str, body: dict) -> Optional[dict]:
        """处理游戏消息（业务逻辑）"""
        if msg_type == "battle.move":
            # 处理移动请求
            return {
                "msg_type": "battle.move_ack",
                "body": {
                    "success": True,
                    "position": body.get("position", {"x": 0, "y": 0}),
                    "timestamp": int(time.time() * 1000)
                }
            }
        
        elif msg_type == "battle.start":
            # 处理战斗开始
            return {
                "msg_type": "battle.started",
                "body": {
                    "battle_id": body.get("battle_id", 0),
                    "enemies": [{"id": 1, "name": "Monster"}],
                    "timestamp": int(time.time() * 1000)
                }
            }
        
        elif msg_type == "chat.send":
            # 处理聊天消息
            return {
                "msg_type": "chat.receive",
                "body": {
                    "channel": "global",
                    "player_id": player_id,
                    "content": body.get("content", ""),
                    "timestamp": int(time.time() * 1000)
                }
            }
        
        elif msg_type == "player.info":
            # 处理玩家信息查询
            return {
                "msg_type": "player.info",
                "body": {
                    "player_id": player_id,
                    "level": 50,
                    "exp": 12345,
                    "gold": 9999,
                    "timestamp": int(time.time() * 1000)
                }
            }
        
        else:
            logger.warning(f"Unknown message type: {msg_type}")
            return None
    
    async def send_test_message(self, player_id: int, msg_type: str = "system.notify"):
        """发送测试消息给玩家"""
        message = {
            "msg_type": msg_type,
            "body": {
                "content": f"Test message from {self.game_id}",
                "timestamp": int(time.time() * 1000)
            }
        }
        success = await self.send_to_player(player_id, message)
        logger.info(f"Test message to player {player_id}: {'success' if success else 'failed'}")
        return success
    
    async def run(self):
        """运行 Game 服务"""
        # 连接 Redis
        await self.connect_redis()
        
        # 启动上行消费者
        consume_task = asyncio.create_task(self.consume_upstream())
        
        logger.info(f"Game {self.game_id} is running")
        
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
        logger.info("Game service stopped")


async def main():
    """主入口"""
    game = GameService()
    await game.run()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Shutdown requested")
