# YESPDF-plus 项目约定

本文件是本仓库的**项目级约定**，与用户级 `~/.dsh/AGENTS.md` 叠加生效。

## 一、协作与节奏

- **不要轮询**：任何人（含 AI 助手）默认**不主动轮询** CI 运行状态、远端仓库状态、日志，除非用户当轮明确要求。提交/推送后说明"已触发"即可，等用户反馈结果。
- **计划优先**：功能改动先落 `docs/PLAN.md` 里程碑，再动代码；每个里程碑独立 PR / 提交组。
- **不本地编译**：本机不安装 Android SDK / Gradle，一切构建与验证走 GitHub Actions。禁止建议"你本地跑一下 gradle"。
- 破坏性或不可逆操作（force push、删除分支、改默认分支、覆盖远端历史）必须先征得用户确认；普通快进 push 属既定流程，可直接执行。

## 二、CI 结构（已定）

| 流水线 | 文件 | 职责 | 明确不做 |
|---|---|---|---|
| 测试 | `.github/workflows/ci-test.yml` | 单元测试 `:app:testDebugUnitTest` | 不打 APK |
| 构建 | `.github/workflows/ci-build.yml` | `assembleDebug` / `assembleRelease` 出 APK 与 mapping | **不跑测试**（`-x test...`） |

- 两者**缓存必须互通**：统一使用同一个 `actions/cache` key
  （`gradle-cache-<os>-<hashFiles(**/*.gradle, gradle-wrapper.properties, gradle.properties)>`，`restore-keys` 前缀相同），
  缓存路径固定为 `~/.gradle/caches`、`~/.gradle/wrapper`、`~/.android/build-cache`。
  不要改用 `setup-gradle` 的自带缓存（其 key 含 workflow 文件名，两个流水线无法互通）。
- 工具链固定：**Gradle 5.4.1 + AGP 3.4.1 只能用 JDK 8**；而 `cmdline-tools` 的 `sdkmanager` 需要 JDK 17。
  因此统一做法：**先用 JDK 17 装 SDK 包，再切回 JDK 8 构建**。不要试图在 JDK 8 下跑 sdkmanager。
- 版本号：`-PBUILD_NUMBER=$((32 + GITHUB_RUN_NUMBER))`、`-PVERSION_NAME=2.3.<run>`，保证可覆盖安装。

## 三、依赖与仓库

- jcenter 已于 2021 年停服。`com.aaron:base`、`parallaxbacklayout`、`realtimeblurview`、`atv` 等仅存于
  **阿里云 jcenter 镜像**（实测 Maven Central 404）。仓库顺序固定：
  `google()` → 阿里云 jcenter → 阿里云 google/public → `mavenCentral()` → `jitpack.io`。
- 新增任何依赖前，先确认在上述仓库可得；不允许再引入 bintray / jcenter 直连。
- 禁止动态版本（如 `1.2.+`、`latest.release`）。

## 四、产品原则

- **文件引用原则（最高优先级）**：只记录用户文件的**路径**，**永不复制用户文件**；导入/打开外部文件只落库路径。
- 唯一的派生文件是封面缩略图：最长边 ≤512px、JPEG q80、单文件 ≤2MB。
- **不产生临时副本**：遇到畸形/损坏 PDF 时，在**内存**内动态修复（PdfBox → 字节缓冲 → 渲染），"将错就错、能渲染即可"，
  只有在内存修复不可行时才降级为"逐页容错 + 明确提示"，**不得**在 `cache/` 或其他目录写出修复副本。
- 用户数据必须**可见、可理解、可清理**：内部目录要在 App 内以"用途名称注释 + 体积 + 条目数"呈现。
- **UI 复用优先**：优先用现有组件（`DialogManager` 的 BottomSheet、`app_recycler_item_settings_*`、`app_sel_*`、红色主题）。
  确需新页面/新图标时，必须在 PR 说明里写清"为什么现有组件不合适"。

## 五、代码规范

- 沿用上游风格：MVP + GreenDAO + EventBus + LiveData/RxJava；新增代码不引入 MVC 框架重写。
- Kotlin 1.3.61 / `kotlin-android-extensions` 合成视图仍是现行写法，不要升级 Kotlin 主版本（会破坏全仓合成视图）。
- 数据库变更必须同时更新 `greendao.schemaVersion` 与 `UpdateOpenHelper` 的迁移分支。
- 中文 commit message，前缀用里程碑号：`build(M0):`、`feat(M2):`、`fix(M1):`、`ci:`、`docs:`。

## 六、安全

- `.secrets/`（含 GitHub PAT）永不入库，`.gitignore` 已覆盖。
- 不读取、不输出、不回显凭据内容；只在需要时使用其路径。
- 用户密码：仅以 Android Keystore 加密后的密文存库，任何 UI / 日志都不得回显明文。
