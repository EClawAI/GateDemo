## 1. 实现

- [x] 1.1 `PlayerData` 增加 `plunderSettlements`；`MongoPlayerPlunderLedger` + `PlayerPlunderLedger` 接口；`PlayerSessionRegistry` / `PlayerSession` 注入链路
- [x] 1.2 `CityWorldStatePersistence` + Mongo 实现；`CityBehavior` load/save；`World`/`Region` 注入
- [x] 1.3 测试：`InMemoryPlayerPlunderLedger`；既有 Actor 测试更新构造参数

## 2. 规格与路线图

- [x] 2.1 OpenSpec 能力 `game-actor-persistence-and-cache-write-order`（proposal/design/tasks/spec）
- [x] 2.2 `docs/pekko-game-actor-openspec-roadmap.md` 阶段 6 链接指向本变更归档
