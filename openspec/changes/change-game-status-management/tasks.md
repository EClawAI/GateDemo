## 1. Game服务修改 - 状态管理

- [ ] 1.1 定义GameStatus枚举（NOT_STARTED=0, STARTED_NOT_LOGIN=1, STARTED_CAN_LOGIN=2）
- [ ] 1.2 创建GameStatusService状态服务
- [ ] 1.3 实现启动时设置状态为"已启动但不可登录"(1)
- [ ] 1.4 实现初始化完成后设置状态为"可以登录"(2)
- [ ] 1.5 实现关闭时设置状态为"未启动"(0)
- [ ] 1.6 实现定时心跳同步状态到Redis
- [ ] 1.7 添加Redis配置到Game服务

## 2. LoginService修改 - 状态过滤

- [ ] 2.1 添加Game状态查询方法（从Redis查询game:status:{gameId}）
- [ ] 2.2 修改路由逻辑，过滤不可用Game（状态!=2）
- [ ] 2.3 实现重定向逻辑（上次服不可用时返回推荐服+提示）
- [ ] 2.4 修改响应结构，添加redirect和redirectMessage字段
- [ ] 2.5 简化登录记录：路由时直接记录playerId->gameId（无需等待通知）

## 3. 配置

- [ ] 3.1 Game服务添加状态同步配置（Redis连接、心跳间隔）
- [ ] 3.2 LoginService添加Game状态查询配置

## 4. 测试

- [ ] 4.1 单元测试Game状态同步
- [ ] 4.2 单元测试Login状态过滤和重定向
- [ ] 4.3 集成测试

## 5. 文档检查与补全

- [ ] 5.1 检查docs目录是否存在Game服务详细设计文档
- [ ] 5.2 检查docs目录是否存在LoginService详细设计文档
- [ ] 5.3 如不存在，创建Game服务详细设计文档
- [ ] 5.4 如不存在，创建LoginService详细设计文档
