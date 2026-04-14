## 1. World / Region / City Actor

- [x] 1.1 实现 `CityBehavior`（绑定 `regionId`/`cityId`、地图缓存占位、`CityEnvelope`/`targetCityId` 校验）
- [x] 1.2 实现 `RegionBehavior`（按 `cityId` 懒创建匿名 City 子 Actor）
- [x] 1.3 实现 `WorldRegistryBehavior`（按 `regionId` 懒创建 Region 子 Actor、`RouteToCity`）

## 2. Spring 与可测性

- [x] 2.1 新增 `WorldRegistryConfiguration` Bean：`SpawnProtocol` 创建 `WorldRegistry` 单例
- [x] 2.2 单测：同一 `(regionId,cityId)` 命令顺序处理；错误 `targetCityId` 在 **错误城**不执行业务接受计数

## 3. 规格与构建

- [x] 3.1 合并 `game-world-region-city-actors` 至 `openspec/specs/`；更新路线图阶段 4 链接
- [x] 3.2 `mvn -pl game-service test` 通过
