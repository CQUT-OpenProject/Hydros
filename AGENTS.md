## Quick Overview

Hydros 是一个用 Kotlin 编写的「趣智校园」第三方 Android 客户端，仅用于课程学习实践。

- Stack: Kotlin, Jetpack Compose (Material 3), 单 Activity + Navigation Compose, AGP 9.x
- Data: Retrofit + OkHttp + kotlinx.serialization, DataStore Preferences, CameraX + ML Kit, MQTT (Paho)
- DI: 手写依赖装配，入口在 `data/HydrosGraph.kt`（无 Hilt/Koin）
- SDK: minSdk 31, targetSdk 35, compileSdk 37；JDK/JVM 21
- Package: `org.cqutosp.hydros`
- Build: 只用 Gradle wrapper (`./gradlew`)；依赖版本集中在 `gradle/libs.versions.toml`

## Product Constraints

- 修改数据收集、权限或网络行为时，同步更新 README 与应用内隐私政策。
- `CAMPUS_BASE_URL` 的默认值刻意由字节数组构造，不要改成明文常量。

## Code Map

- `data/network/` 网络协议与恢复策略；`data/repository/` 业务仓库；`data/storage/` 应用数据持久化；`data/ble/` 附近设备；`data/update/` 应用更新。
- `ui/` 为单 Activity Compose：`HydrosViewModel` 暴露单一 `HydrosUiState`（`StateFlow`），页面在 `screens/`，复用组件在 `components/`。
- 用户可见错误统一走 `data/feedback/Failures.kt`（`FailureOperation` / `FailureStage` / `FailureReason`）与 `ui/state/SessionFeedback.kt`，不要在页面里另造错误文案映射。
- UI 文案为简体中文，沿用直接写在 Compose 中的现状（`strings.xml` 目前只有 `app_name`），不要顺手引入整套字符串资源或 i18n。

## Write Code

- 先读同包相邻文件，沿用现有的状态流、错误边界、Compose 组件与命名方式。
- 可直接完成实现与验证，不必等待计划批准；可逆的实现细节自行决定。只在影响验收的歧义、数据丢失、破坏外部协议或新增依赖时提问。
- 不要引入新依赖、新框架或新架构层，除非明确要求；优先复用现有库与组件。
- 改动保持聚焦；不要顺带重构或格式化无关文件。

## Commit Convention

Use Gitmoji format: `<emoji> <concise Chinese>` (no `feat:`/`fix:` prefix). e.g., `✨ 新增课表导出功能`.
Do not commit / push unless explicitly requested; reporting completion ≠ committing.
