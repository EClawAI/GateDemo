## Why

当前存在以下代码复用问题：
- Proto 生成代码在 gate-service 和 game-service 各自编译，重复维护
- ApiResponse 在 login-service 和 center-service 重复定义
- 无共享 DTO/工具模块，公共能力无法复用

## What Changes

新建 common Maven 模块统一公共能力：
- 新建 common Maven 模块
- 将 Proto 生成类、共享 DTO（如 ApiResponse）、工具类集中到 common
- 其余模块依赖 common，移除各自重复实现

## 核心功能

1. **common Maven 模块创建**
   - 新建 common 子模块
   - 配置 pom 依赖关系

2. **Proto 生成类统一**
   - Proto 编译产出移至 common
   - gate-service、game-service 依赖 common 获取生成类

3. **共享 DTO 抽取**
   - ApiResponse 等通用响应结构移至 common
   - login-service、center-service 移除重复定义

4. **工具类聚合**
   - 公共工具类迁移至 common
   - 各服务通过依赖引用

## Impact

- 影响全部服务 `pom.xml` 依赖调整
- 影响 `gate-service` Proto 依赖移除、引用 common
- 影响 `game-service` Proto 依赖移除、引用 common
- 影响 `login-service` ApiResponse 移除、引用 common
- 影响 `center-service` ApiResponse 移除、引用 common
