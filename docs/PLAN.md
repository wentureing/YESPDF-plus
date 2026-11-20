# YESPDF 强化升级计划

- 原则：**计划优先、git 全量追踪、不本地编译、GitHub Actions 出包、占满缓存**
- 约束：UI 复用现有组件与视觉语言；**永不复制用户文件**；用户数据可审计、可清理
- 基线：上游 `c2428c2`（v2.2.3），本仓库保留完整历史，`upstream` 为上游远端

## 0.0 优先级修订（用户指示：功能优先，故障修复挂起）

**当前执行序列（功能）**

| 编号 | 功能 | 对应需求 | 状态 |
|---|---|---|---|
| F0 | **内部文件管理**：三点菜单 →「管理」；列出私有目录并中文注释、开放安全边界内的编辑（详见 `docs/FILE_MANAGER_DESIGN.md`） | 需求 8 | 已实现 S1/S2/S4：概览、目录导航、中文注释（含封面↔PDF 名称匹配）、查看信息/重命名/复制路径、多选复制粘贴、导出到 Download、数据库高危删除；S3 系统 Picker 双入口 + S5 封面替换待做 |
| F1 | 夜间显示拆分：反色（黑白文档）/ 蒙版（彩色文档），单击切「关↔上次模式」、长按二选一 | 需求 3 | 已实现，待装机验证 |
| F2 | 加密 PDF：密码 Keystore 加密持久化 + 静默重试 + 「记住密码」勾选（默认勾选） | 需求 2 | 待做 |
| F3 | 自动扫描规则（默认扫描路径 + 排除路径/文件，勾选生效）与「浏览全部」排除开关 | 需求 5、6 | 待做 |
| F4 | 用户数据可见：内部目录用途注释 + 体积 + 条目数 + 清理入口 | 需求 8 | 待做（不含封面限流） |

**挂起（故障修复类，用户明确要求后置）**

- M2 内存与渲染层（预算制缓存、瓦片上限、双质量渲染、`closePage`、pdfium 内核升级）
- M6 畸形 PDF 容错（内存内动态修复）
- `PdfUtils` 的 `MODE_READ_WRITE` + fd 泄漏修复（虽属缺陷，但归入故障类一并后置）
- 封面限流（≤512px/≤2MB）——与 F4 同文件，待 F4 时一并处理

## 0. 里程碑总览

| 里程碑 | 内容 | 交付物 | 依赖 |
|---|---|---|---|
| M0 | 构建基线：仓库源改造 + 签名兜底 + CI 流水线 | 首个 CI 绿包（debug/release APK） | — |
| M1 | 数据与目录重构：封面限流、内部目录结构化、存储与数据页 | APK + `docs/DATA_LAYOUT.md` | M0 |
| M2 | 内存与渲染层：预算制缓存、瓦片上限、双质量渲染、原生页回收、fd 泄漏修复 | APK + `docs/PERF.md` | M0 |
| M3 | 加密 PDF：密码持久化（Keystore 加密）+ 自动重试 + 密码管理 | APK | M1 |
| M4 | 反色 / 蒙版：模式拆分 + 长按选择 + 蒙版强度 | APK | M0 |
| M5 | 扫描与浏览规则：默认扫描路径、排除路径/文件、浏览页开关 | APK | M1 |
| M6 | 畸形 PDF 容错：诊断链路 + 逐页降级 + 可选修复副本 | APK + 样本回归清单 | M2 |

每个里程碑独立 PR、独立 commit 组，CI 出 APK 后由用户装机验收，再进下一个。

---

## M0 构建基线（先让 CI 跑绿，零业务改动）

改动点：

1. `build.gradle` / `app/build.gradle` / `android-pdf-viewer/build.gradle` 仓库与依赖：
   - 删除 `jcenter()`、`dl.bintray.com/umsdk`；新增 `maven { url 'https://maven.aliyun.com/repository/jcenter' }`（实测全部 404 依赖均 200）
   - 顺序：`google()` → aliyun-jcenter → `mavenCentral()` → aliyun-google → `jitpack.io`
2. 签名不再强依赖 `local.properties`：读取顺序 `env(STORE_FILE_NAME/…)` → `local.properties` → 回退 `debug` 签名（CI 用仓库 secrets 注入 `keystore.b64`）
3. `.github/workflows/android.yml`：
   - `ubuntu-latest` + `temurin 8`（AGP 3.4.1 / Gradle 5.4.1 只能 JDK 8）
   - `gradle/actions/setup-gradle` 自动缓存 `~/.gradle/caches`、wrapper；额外缓存 `~/.android/build-cache`
   - `./gradlew :app:assembleDebug :app:assembleRelease --no-daemon --stacktrace`
   - 上传 APK + `mapping.txt`（release）为 artifact，release 打 tag 时创建 GitHub Release
   - 触发：`push`（main/dev）、`pull_request`、`workflow_dispatch`
4. vendor 兜底（可选但建议）：把 `com.aaron:base` 等 5 个 jcenter-only 构件下载进 `vendor/maven/`，仓库改为 `maven { url "$rootDir/vendor/maven" }` 优先，彻底摆脱外部镜像依赖

验收：CI 首次绿色，产物可安装。

---

## M1 数据与目录重构（对应需求 8）

**先修三个真实缺陷**（都是"打不开/崩溃"的隐藏元凶）：

- `PdfUtils` 中 `ParcelFileDescriptor.open(..., MODE_READ_WRITE)` 且从不 `close()` → 只读存储位置直接 `SecurityException`，且持续 **fd 泄漏**（批量导入后 `EMFILE`）。改 `MODE_READ_ONLY` + `try-with-resources`。
- 封面 `saveBitmap` 无尺寸/质量上限（全屏宽 PNG，单本可达数 MB，100 本 ≈ 数百 MB 且导入期内存峰值高）→ 改为最长边 ≤512px、JPEG q=80、单文件 ≤2MB 硬上限，超限跳过。
- 封面/页数走系统 `PdfRenderer`，与 pdfium 判定不一致 → 统一以渲染层为准（`PdfRenderer` 仅作封面兜底）。

目录规划（`filesDir`/`cacheDir`，全部写进 `docs/DATA_LAYOUT.md` 并在 App 内可见）：

| 路径 | 用途 |
|---|---|
| `files/db/YESPDF.db`(+`-wal`,`-shm`) | 书库索引：书签、进度、分组、密码条目 |
| `files/covers/<id>.jpg` | 首图封面，≤512px / JPEG80 / ≤2MB（**唯一的派生文件**） |
| `cache/thumbs/` | 二次采样缩略图，可随时清空 |
| `cache/repair/` | 畸形 PDF 的临时修复件（仅 M6、且用户显式开启时产生） |
| `logs/` | 可选诊断日志，默认关闭 |

新增「存储与数据」页面（入口：`AboutActivity` + `SettingsActivity` 底部行）：
- 逐条展示：路径、**用途名称注释**、占用大小、条目数、单项清理按钮
- 数据库区：PDF/分组/最近/书签/密码条目计数，导出 `index.json`（不含密码明文）
- 交互全部复用 `app_recycler_item_settings_switch` / `app_bottomdialog_delete` / `DialogManager`
- 参考「阅读 Sigma」的初级文件管理：只读展示 + 清理；我们额外提供**用途注释**与"打开目录"（`FileProvider` + `ACTION_VIEW`）

验收：导入 50 本 PDF，`covers` 总量与单文件上限达标；`lsof`/`/proc` 无 fd 增长；任一目录可在 App 内看到并清理。

---

## M2 内存与渲染层（对应需求 1，核心）

问题定位（已读码确认）：

1. `CacheManager` 只按**瓦片数量**（120）淘汰，不按字节 → 单片尺寸随缩放放大时总量可达上百 MB
2. `PART_SIZE=256` 的除法换算让单片实际尺寸约等于"整页在当前缩放下的像素"，缺乏硬上限
3. `PdfFile.openPage` 打开即不关；`pdfium-android:1.9.0` **无 `closePage`**（已解包 aar 确认）→ 原生内存只增不减
4. 快速滚动时新旧 part 一起挤在 `passiveCache`，`makeANewSet` 只搬家不淘汰
5. 惯性滚动也按最高质量渲染，渲染队列在滚动中堆积

方案（按收益排序）：

- **A. 预算制缓存**：`CacheBudget.maxBytes = clamp(maxMemory/10, 24MB, 96MB)`；`CacheManager` 维护累计字节数，超预算时淘汰"离当前页最远且非 active"的瓦片；`onTrimMemory` 再压到 1/2
- **B. 瓦片上限**：`PART_SIZE` 按屏幕短边像素动态（`clamp(shortSide*dpi*0.35, 256, 512)`），`partRenderWidth/Height` 再 `clamp(≤2048)`；避免"一片=整页"
- **C. 双质量渲染**：滚动/惯性中只渲染视口内 + 半屏预取、`bestQuality=false`；静止 300ms 后对本屏补一次高质量（新增 `PDFView.setRenderProfile(...)`）
- **D. 原生页回收**：换 `io.legere:pdfiumandroid:1.0.32`（实测有 `PdfiumCore.closePage`，aar 10.2MB < 现 19.2MB，ABI arm64/armv7/x86/x86_64 齐全），`PdfFile` 维护页 LRU（上限 ~24 页）并对淘汰页 `closePage`
  - 迁移面：`com.shockwave.pdfium` → `io.legere.pdfiumandroid`，涉及 `PDFView`/`PdfFile`/`DecodingAsyncTask`/`source/*` 6 个文件
  - **风险**：API 签名差异只能靠 CI 编译+装机验证；若失败则回退「不换库 + A/B/C/E」策略（内存收益约 60%）
- **E. 崩溃兜底**：`OutOfMemoryError` 捕获 → 预算减半 + 重载当前页（不退出）；跳过超大页（>8192px 边）并提示
- **F. 大文件策略**：页数 > 2000 时自动进入"精简模式"（预取 0.5 屏、预算 24MB、默认关反色/蒙版叠加）

验收：顶部 3000 页 PDF 连续快速滚动 3 分钟不崩；`dumpsys meminfo` PSS 峰值 < 1/3 heap；返回书库后 PSS 回落。

---

## M3 加密 PDF（对应需求 2）

- Schema v4：`PDF` 增 `pwdBlob TEXT`（Keystore AES-GCM 密文 + IV，base64），`MigrationHelper` 迁移
- `PasswordVault`：Android Keystore 别名 `yespdf.pdf.pwd`，AES-256-GCM；解密失败（换机/清 key）静默清除并走输入流程
- 打开流程：有密文 → 先静默尝试 → `PdfPasswordException` 才弹现有 `app_dialog_input`（文案已有 `app_need_verify_password`）；成功后可勾选"记住此文件密码"
- 密码管理：存储与数据页 + 预览页菜单「清除已存密码」；只显示"已保存/未保存"，永不回显明文
- 兼容：`password` 仍走 `onSaveInstanceState`，避免旋转丢失

验收：加密 PDF 二次打开不再输入；错误密码提示沿用现有 toast；清 key 后行为可降级。

---

## M4 反色 / 蒙版（对应需求 3、7）

- 数据：`NightDisplayMode { OFF, INVERT, MASK }`，`SP_NIGHT_DISPLAY`，旧 `SP_NIGHT_MODE` 一次性迁移
- 语义：反色 = 现有 `ColorMatrix` 负片（黑白文档）；蒙版 = 在 `app_pdfview` 之上叠加半透明黑层（彩色文档保色只压亮度）
- UI（复用现有组件，位置不变）：左下角 `app_night_btn` 保留
  - 单击 = 「关 ↔ 上次启用模式」切换（不破坏现有肌肉记忆）
  - 长按 = 现有 BottomSheet 风格二选一（`app_bottomdialog_*` 样式 + 一行建议文案："黑白文件用反色，彩色文件用蒙版"）
  - 蒙版强度：设置页复用 `app_recycler_item_settings_seekbar`（10%~90%，默认 65%）
- 实现细节：`app_night_mask` 是一个 `View`（`match_parent`、`clickable=false`、默认 `gone`）；反色时 PDFView 背景黑、蒙版模式背景白；切换通过 `PDFView.setNightMode` + mask 可见性，无需重建文档

验收：四种组合（横/竖 × 反色/蒙版）切换无闪烁、无重载、无内存增长。

---

## M5 扫描与浏览规则（对应需求 5、6）

- 数据：新表 `ScanRule(id, kind, value, enabled, position)`，`kind ∈ {INCLUDE_DIR, EXCLUDE_DIR, EXCLUDE_FILE}`；schema v4 一并迁移
- 规则引擎：`ScanRuleFilter`（实现 `FileFilter`）包装现有 `FileFilterImpl`，供 `ScanActivity` 与 `ViewAllModel` 共用
- 「自动扫描」页：在"立即扫描"下新增一行「扫描范围与排除规则」→ 新页 `ScanRuleActivity`
  - 顶部 switch「启用规则」（默认关，符合"通常用不上"）
  - 「默认扫描目录」（默认预置当前 SD 卡根，可增删）
  - 「排除路径」「排除文件（支持 `*.pdf`、`~$*` 之类通配）」
  - 增删交互：现有 `DialogManager.createInputDialog` + 长按 `app_bottomdialog_delete`
- 「浏览全部」页：路径栏右侧加一个筛选 `ImageButton`（复用 `app_ic_action_selectall_*` 选中态风格），单击切换「应用排除规则」，状态 SP 持久化，默认关

验收：规则开启后，扫描结果与浏览列表同时排除指定目录/文件；关闭后恢复全量。

---

## M6 畸形 PDF 容错（对应需求 4）

> 需真实样本。请提供 1~2 个"页面被移除但文件大小未变"的 PDF，才可精准定位（CI 里可做空跑解析测试）。

分层策略：

1. **诊断**：打开时若 pdfium 报错 → 记录 `PdfDocument.meta + 实际可打开页数`；声明页数 > 实际页数时以实际为准并提示"文档声明 N 页，实际可渲染 M 页"
2. **逐页降级**：`onPageError` 已有回调 → 该页背景绘制"页面不可用"占位（复用 `app_ic_placeholder_*`），不再整体退出（现在只在页级 toast）
3. **结构修复**（可选，用户开关）：`PdfBox-Android` 重新解析并写出 `cache/repair/<id>.pdf`（**仅缓存目录、可一键清理、不作为书库文件**），修复失败则回退原文件
4. **页表修复**：若仅 xref/页树损坏，`PdfBox` 逐页 `PDDocument` 重建页树后同上输出

> 与"永不复制文件"的关系：修复副本只存在于 `cache/`（系统可回收目录），且必须由用户显式开启；这需要你在决策项中确认。

---

## CI 流水线设计（.github/workflows/android.yml）

```
jobs.build
  runs-on: ubuntu-latest
  steps:
    - actions/checkout@v4 (fetch-depth 1)
    - actions/setup-java@v4  (temurin, 8)
    - gradle/actions/setup-gradle@v3   # 自动缓存 ~/.gradle
    - actions/cache@v4  (key: android-build-cache-${{ hashFiles('**/*.gradle') }})
    - 解码 secrets.KEYSTORE_B64 → app/release.jks（无 secret 则跳过，用 debug 签名）
    - ./gradlew :app:assembleDebug :app:assembleRelease --no-daemon --stacktrace -PBUILD_NUMBER=$GITHUB_RUN_NUMBER
    - actions/upload-artifact@v4  (APK × N, mapping.txt, docs)
```

缓存要点：`~/.gradle/caches`、`~/.gradle/wrapper`、`~/.android/build-cache`、`~/.m2`（若 vendor 化则命中率接近 100%）。

版本号策略：`versionCode = 32 + GITHUB_RUN_NUMBER`，`versionName = 2.3.<run>`；release tag 触发 GitHub Release 附加 APK。

---

## 需要你决策的事项

1. 仓库落地：在 `zhazha-xiong` 下新建仓库的名称与可见性（public/private）
2. pdfium 内核：换 `io.legere:pdfiumandroid`（可真正释放原生页内存）还是保守留 1.9.0
3. 夜间模式交互：单击切「关/上次模式」+ 长按二选一，是否符合预期
4. 密码保存：默认"记住"还是每次询问后可选记住
5. 畸形 PDF：是否允许在 `cache/` 生成临时修复副本（唯一一处"派生文件"例外）

（详见本轮对话中的选项；确认后我按 M0 → M6 顺序推进，每个里程碑一个 PR。）
