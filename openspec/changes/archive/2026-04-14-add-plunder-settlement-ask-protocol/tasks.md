## 1. Player 与 Registry

- [x] 1.1 `PlunderSettleResponse` 与 `PlayerSessionBehavior.SettlePlunder` + 钱包/幂等表
- [x] 1.2 `PlayerSessionRegistryBehavior.GetPlayerSession`

## 2. City / World 装配

- [x] 2.1 `CityBehavior.SettlePlunderVictim` + `pipeToSelf` Ask 链
- [x] 2.2 `WorldRegistry` / `Region` / `City` 注入 `playerSessionRegistry`；`WorldRegistryConfiguration` 依赖

## 3. 测试与规格

- [x] 3.1 `PlunderSettlementIntegrationTest`（重复 battleId、离线）
- [x] 3.2 更新 `WorldRegionCityRoutingTest` / `CityBehaviorTest` 类型与构造
- [x] 3.3 `mvn -pl game-service test` 通过
