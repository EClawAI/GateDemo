# Tasks: pekko-game-baseline-and-bom

## 1. 父 POM 与 JDK

- [x] 1.1 在根 `pom.xml` 将 `java.version`（及 `maven.compiler.source` / `target`，若存在）设为 **21**
- [x] 1.2 确认 `maven-compiler-plugin` 配置与 Java 21 一致（沿用父级或显式 `release`/`source`/`target`）

## 2. Pekko BOM

- [x] 2.1 在根 `pom.xml` 的 `dependencyManagement` 中 **import** `org.apache.pekko:pekko-bom_2.13:1.5.0`（`type`/`scope` 为 `pom` / `import`）
- [x] 2.2 在 `dependencyManagement` 或注释中约定：子模块声明 `pekko-*_2.13` 时不写版本号（与 `pekko-game-baseline` 规格一致）

## 3. 验证与文档

- [x] 3.1 在仓库根执行 `mvn -q -DskipTests compile`（或团队标准 CI 命令）通过
- [x] 3.2 （可选）在 `docs/pekko-game-actor-openspec-roadmap.md` 阶段 0 行增加指向本 change 的 `proposal.md` 链接，便于导航

## 4. 后续（单独 change，不在此清单勾选）

由后续 change `integrate-pekko-actor-system-in-game-service` 向 `game-service` 增加 **`pekko-actor-typed_2.13`**（不写版本）并实现 `ActorSystem` 生命周期。
