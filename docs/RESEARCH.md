# YESPDF 生态与技术调查（2026-11 快照）

> 调查对象：<https://github.com/aaronzzx/YESPDF>（Apache-2.0，80 star，9 fork，最后功能提交 `c2428c2 release 32-v2.2.3`，仓库 2025-05 仍有推送）
> 本仓库以该提交为基线（保留完整 git 历史），remote `upstream` 指向上游 master。

## 1. 上游工程画像

| 维度 | 事实 |
|---|---|
| 形态 | 单模块 Android App + 内嵌 library 模块 `android-pdf-viewer` |
| 语言 | Java + Kotlin 混编（Kotlin 1.3.61，`kotlin-android-extensions` 合成视图） |
| 架构 | 自写 MVP（`IViewAllContract` / `PreviewActivity` 直连）+ EventBus + LiveData + RxJava2 + AutoDispose |
| 持久化 | GreenDAO 3.2.2（`PDF` / `Collection` / `RecentPDF` 三张表，schemaVersion 3），由 `UpdateOpenHelper` + `MigrationHelper` 迁移 |
| 渲染 | 内嵌 `android-pdf-viewer` 3.2.0-beta.1（barteksc 分支），底层 `com.github.barteksc:pdfium-android:1.9.0` |
| UI | 传统 View 体系：ButterKnife 10.1.0 + ConstraintLayout + Material 1.3.0-alpha01 + 大量 `@drawable/app_*` 资源前缀 |
| 构建 | AGP 3.4.1 + Gradle 5.4.1 + compileSdk 29 / minSdk 21 / targetSdk 28，签名信息读 `local.properties` |
| 特有依赖 | `com.aaron:base:1.1.5-beta9`（作者自研基础库，提供 `UiManager`/`OnClickListenerImpl`/`TextWatcherImpl`/`StatusBarUtils`，并传递 utilcodex/RxJava/Retrofit/autodispose）、`parallaxbacklayout`、`realtimeblurview`、`atv`、`BaseRecyclerViewAdapterHelper`、友盟 SDK |

## 2. 生态关系

```
                     ┌──────────────────────────────┐
   Chrome / Android  │  PDFium (C++, Google)        │  Android 5+ 系统 PdfRenderer 亦基于它
   PdfRenderer ──────┤  FPDF_* API                  │
                     └───────────┬──────────────────┘
                                 │ JNI 绑定
                  ┌──────────────┴───────────────┐
                  │ shockwave/pdfium-android     │  1.9.0 = 本项目所用，2018 年后停更
                  │ (com.github.barteksc:        │  维护替代：io.legere:pdfiumandroid（含 closePage）
                  │  pdfium-android:1.9.0)       │           com.github.mhiew:pdfium-android
                  └──────────────┬───────────────┘
                                 │ View 层封装（瓦片渲染/手势/缓存）
                  ┌──────────────┴───────────────┐
                  │ AndroidPdfViewer 3.2.0-beta.1│  作者仓库已迁移：barteksc → DImuthuUpe/AndroidPdfViewer
                  │ (com.github.barteksc.        │  （8474 star，2025-11 仍活跃；mhiew 等 fork 维护）
                  │  pdfviewer.*)                │
                  └──────────────┬───────────────┘
                                 │ 二次开发
                  ┌──────────────┴───────────────┐
                  │ YESPDF (本仓库)               │  内嵌 fork（模块化而非 aar 依赖，可直接改渲染层）
                  └──────────────────────────────┘

  平行生态（可借鉴 / 可选替换，均非本项目依赖）：
  · MuPDF (Artifex, AGPL)            —— 渲染质量与容错强，KOReader 等采用；商业授权限制
  · PdfBox-Android (TomRoush, Apache)—— 纯 Java，强项是加密解密、文本抽取、畸形 PDF 容错修复
  · PDF.js (Mozilla)                 —— WebView 方案，内存表现取决于 WebView
```

结论：**YESPDF 属于「AndroidPdfViewer 二次开发」这一支，其能力上限很大程度由内嵌 PDFView 的瓦片渲染与缓存策略决定**，所以"大文件崩溃"的修复必须动 `android-pdf-viewer` 而不是只改 App 层。上游把 library 以源码模块内嵌，正是我们能安全改渲染层的前提。

## 3. 现状核查（针对本次 8 项需求）

| 需求 | 现状（代码证据） | 判定 |
|---|---|---|
| 1 内存/崩溃 | `Constants.Cache.CACHE_SIZE = 120` 个瓦片位图；`PART_SIZE = 256` 但实际瓦片尺寸 = `PART_SIZE/pageRelativePartSize`，会随页面缩放放大到接近整页；`CacheManager` 只按**数量**淘汰，从不按字节；`PdfFile.openPage` 打开后**永不关闭**（1.9.0 的 `PdfiumCore` 无 `closePage`，已实测 aar）；`PagesLoader` 每次滚动 `removeMessages` 但已渲染 part 仍占位图 | 需改渲染层 |
| 2 加密 PDF | 已有完整骨架：`PdfPasswordException` → `inputDialog`（`app_need_verify_password`），`password` 仅存活于 Activity（`onSaveInstanceState`），**未持久化、不自动重试** | 只需加持久化 |
| 3 夜间模式 | 单一开关，实现是 `ColorMatrix` 反色（`PDFView.setNightMode`），即"反色"；无"蒙版" | 需拆分 |
| 4 畸形 PDF | `onError` → `app_doc_parse_error` 弹窗后只能退出；`PdfUtils` 用 `MODE_READ_WRITE` 打开文件（对只读路径会 `SecurityException`）；封面/页数走系统 `PdfRenderer`，与 pdfium 判定不一致 | 需容错链路 |
| 5 自动扫描规则 | `ScanActivity.traverseFile()` 遍历**所有** SD 卡根目录，`FileFilterImpl` 仅排除 `.` 开头与后缀 | 无任何规则 |
| 6 浏览全部排除 | `ViewAllModel` → `ByNameListable`(固定 `FileFilterImpl`)，无排除能力，也无开关 | 无 |
| 7 UI 复用 | 已有可复用资产：`DialogManager`（alert/input/scan/import/grouping 五种 BottomSheet）、`app_recycler_item_settings_switch|seekbar|recent_count`、`app_sel_*` 选择器、`app_ic_*` 图标、统一红色主题 | 可满足 |
| 8 文件引用原则 | **已满足"不复制文件"**：导入只落 `PDF.path`（唯一索引），封面是首图缩略图（`PdfUtils.saveBitmap` → `files/<name>.jpg`）。**不足**：封面无尺寸/大小上限（全屏宽 PNG，可数 MB）；内部目录无结构、无用途注释、无清理入口、用户不可见 | 强化 |

## 4. 构建阻塞（决定 CI 方案）

| 阻塞 | 事实 | 处置 |
|---|---|---|
| jcenter 已下线 | `com.aaron:base:1.1.5-beta9`、`parallaxbacklayout`、`realtimeblurview`、`atv`、butterknife、greendao 在 Maven Central **均 404** | 实测 **阿里云 jcenter 镜像全部 200**，作为仓库首选；关键 aar 建议 vendor 进仓库兜底 |
| 老工具链 | AGP 3.4.1 + Gradle 5.4.1 只能跑 JDK 8 | CI 固定 `temurin 8`，先"零改动跑绿"，再单独立项评估 AGP 升级 |
| 签名 | `app/build.gradle` 在 `signingConfigs` 里直接读 `local.properties`，CI 无此文件会**配置阶段即失败** | 改为「环境变量/`local.properties` 双通道，缺失则回退 debug 签名」 |
| 友盟 SDK | `dl.bintray.com/umsdk` 已挂；`com.umeng.umsdk:common:9.1.0` 在 Maven Central 可用 | 仓库去掉 bintray，改用 Central |
| 无本地编译 | 用户约束 | 一切验证交给 GitHub Actions |
