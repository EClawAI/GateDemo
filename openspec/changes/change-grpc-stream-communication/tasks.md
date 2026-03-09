## 1. Proto定义修改

- [x] 1.1 修改 `proto/game_service.proto`，将消息体从 `string` 改为 `bytes`
- [x] 1.2 添加新的Stream RPC方法定义
- [x] 1.3 重新生成Java代码 (`mvn compile`)

## 2. Gate服务修改

- [ ] 2.1 修改 `GameGrpcClientPool`，实现Bidirectional Stream连接
- [ ] 2.2 添加Stream消息发送方法
- [ ] 2.3 添加Stream消息接收处理
- [ ] 2.4 实现连接断开重连机制

## 3. 二进制序列化

- [ ] 3.1 添加Protobuf序列化工具类
- [ ] 3.2 修改消息发送逻辑，使用Protobuf序列化
- [ ] 3.3 修改消息接收逻辑，使用Protobuf反序列化

## 4. Game服务修改

- [ ] 4.1 修改game-service的proto定义（与Gate保持一致）
- [ ] 4.2 实现Stream服务端接口
- [ ] 4.3 测试Gate↔Game通信

## 5. 测试与验证

- [ ] 5.1 单元测试Stream通信
- [ ] 5.2 集成测试Gate↔Game消息转发
- [ ] 5.3 性能测试对比（JSON vs Protobuf）
