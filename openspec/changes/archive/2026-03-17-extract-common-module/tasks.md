## 1. 创建 common 模块

- [ ] 1.1 在项目根目录下创建 `common/` 目录及 `common/pom.xml`
- [ ] 1.2 在父 pom 的 modules 中增加 `<module>common</module>`
- [ ] 1.3 在 dependencyManagement 中增加 common 依赖声明
- [ ] 1.4 创建 `common/src/main/java/com/clawai/gatedemo/common/` 包结构

## 2. Proto 编译迁移至 common

- [ ] 2.1 在 common/pom.xml 中配置 protobuf-maven-plugin，protoSourceRoot 指向 proto 目录
- [ ] 2.2 从 gate-service/pom.xml 移除 protobuf-maven-plugin 配置
- [ ] 2.3 从 game-service/pom.xml 移除 protobuf-maven-plugin 配置
- [ ] 2.4 在 gate-service、game-service 的 pom.xml 中添加对 common 的依赖
- [ ] 2.5 更新 gate-service、game-service 中所有 proto 生成类的 import 路径（若有包名变化）
- [ ] 2.6 验证 gate-service、game-service 编译通过且 gRPC 功能正常

## 3. ApiResponse 抽取

- [ ] 3.1 在 common 中创建 `com.clawai.gatedemo.common.dto.ApiResponse`，实现与 login/center 现有逻辑一致
- [ ] 3.2 在 login-service 中删除 `login.model.ApiResponse`，将 import 改为 common.dto.ApiResponse
- [ ] 3.3 在 center-service 中删除 `center.model.ApiResponse`，将 import 改为 common.dto.ApiResponse
- [ ] 3.4 验证 login-service、center-service 的 HTTP 响应格式与迁移前一致

## 4. 工具类聚合（可选）

- [ ] 4.1 识别被多模块引用的工具类（如 TraceIdGenerator）
- [ ] 4.2 将选定工具类迁移至 common.util 包
- [ ] 4.3 更新原模块的 import 与依赖
- [ ] 4.4 验证各模块编译与测试通过

## 5. 文档与验证

- [ ] 5.1 在 README 或开发文档中说明 common 模块职责与依赖关系
- [ ] 5.2 执行全项目 `mvn clean install` 确保所有模块构建成功
- [ ] 5.3 确认 Proto 变更流程：仅需在 common 中编译，gate/game 自动获得新类
