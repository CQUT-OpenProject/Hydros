## 未发布阶段的数据契约

Hydros 目前尚未发布，保持自有数据库、数据结构与线格式版本固定为 `1`。直接维护唯一当前结构，不新增升级链、旧格式分支、兼容别名或旧数据补迁移。开发数据失效时手动清空并重新导入，不在启动时自动删除。此约定不改变产品发布号、随宿主发布的插件版本、第三方依赖或外部标准版本。

## Commit Convention

Use Gitmoji format: `<emoji> <concise Chinese>` (no `feat:`/`fix:` prefix). e.g., `✨ 新增课表导出功能`.
Do not commit / push unless explicitly requested; reporting completion ≠ committing.
