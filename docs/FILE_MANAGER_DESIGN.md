# 内部文件管理（设计稿，未编码）

> 目标：给用户一个**看得见、看得懂、能动手**的私有数据管理页，参照「阅读 Sigma」的初级文件管理，但更进一步：
> 每个条目都有中文用途注释，且**开放编辑**（在安全边界内）。
> 参考基线：`app_pw_main.xml` + `app_activity_settings.xml` + `app_recycler_item_filepicker.xml`（全部已存在）。

## 1. 结论先行：这功能可以很轻

| 需求 | 轻量实现 | 新增量 |
|---|---|---|
| 右上角三点进「管理」 | 三点 = `R.id.app_more` → `app_pw_main.xml` 这个 PopupWindow，**加一个 `TextView` 行** | 8 行 XML + 6 行 Kotlin |
| 首次进入的专业性提示 | 复用 `DialogManager.createDoubleBtnDialog()`（已有）+ 一个 SP 标记 | 10 行 |
| 列表 + 目录导航 | 复用 `app_activity_settings.xml`（toolbar + RecyclerView）与 `app_recycler_item_filepicker.xml`（图标/标题/描述/箭头） | 2 个新文件（Activity + Adapter） |
| 中文注释 | 一张 `Map<pattern, 注释>` 常量表 + 「封面 ← PDF 名称」动态匹配 | 1 个 object（~60 行） |
| 编辑能力 | 全部复用现成 dialog：`createInputDialog`（重命名）、`createDeleteDialog`（删除）、`createAlertDialog`（文本查看/风险确认） | 0 新组件 |
| 打开/导出 | `FileProvider`（已有，`AppConfig.AUTHORITY`）+ `ACTION_SEND`；**不需要**新增依赖 | 3 行 `app_file_paths.xml` |

**不需要**：新依赖、新数据库表、DB 迁移、MVVM 重构、任何图标资源（全部用已有 `app_ic_*`）。

## 2. 数据来源：运行时扫描，不写死路径

现实里本 App 会写文件的地方只有一处（`DBHelper.kt:270` 的封面 `PathUtils.getInternalAppDataPath()/$name.jpg`），但**不同 blankj 版本该 API 指向 `files/` 还是 `/data/data/<pkg>/` 存在差异**，所以设计上不猜：

扫描 5 个根，去重后统一展示，并**始终显示真实绝对路径**：

| 根 | 来源 API | 预期内容 |
|---|---|---|
| `filesDir` | `context.filesDir` | 封面 `*.jpg`（若上面的 API 指向 files） |
| `dataDir` | `PathUtils.getInternalAppDataPath()`（兼容根） | 同上，或空 |
| `databases/` | `context.getDatabasePath(AppConfig.DB_NAME).parent` | `yespdf.db` / `-wal` / `-shm` / `-journal` |
| `shared_prefs/` | `context.filesDir.parent + "/shared_prefs"` | `SPStaticUtils.xml`（blankj 默认文件名） |
| `cacheDir` | `context.cacheDir` | 系统/图片缓存，可随时清 |

## 3. 中文注释规则（"非常确定的目录"必须注释）

### 3.1 静态表（`InternalDataMap`）

| 匹配规则 | 注释 | 可编辑性 |
|---|---|---|
| `yespdf.db` | 书库数据库：书名·路径·阅读进度·书签·分组 | 只读浏览 / 可导出 / 可删除（高危二次确认） |
| `yespdf.db-wal`、`-shm`、`-journal` | 数据库临时日志，退出应用后可安全删除 | 可删 |
| `shared_prefs/SPStaticUtils.xml` | 应用设置：夜间显示、滚动速度、最近阅读数量等 | **只读**（可"恢复默认"= 删除该文件） |
| `cache/` 及子项 | 缓存目录，清空不影响书库 | 可清空 |
| `databases/` | 数据库所在目录 | 不可删 |
| `code_cache/`、`app_*` 系统目录 | 系统托管，不建议手动操作 | 隐藏（默认不展示） |

### 3.2 动态注释（核心差异化：用 PDF 名称匹配）

封面文件名就是 `PDF.name + ".jpg"`，因此：

1. 启动管理页时，把 `DataManager.getPdfList()` 建一张 `cover 绝对路径 → PDF` 映射（也支持仅按 `name` 匹配，容错路径变更）；
2. 命中则注释显示为：`《三体》的封面缩略图 · 唯一派生文件`，副标题追加 `关联原文件：/storage/.../三体.pdf` 与 `阅读进度 62%`；
3. 未命中的 `*.jpg` 标注为：`未被书库引用的封面（可能是残留，可安全删除）` —— 这正好是用户能自己清理垃圾的抓手。

### 3.3 未知项

`未知文件 · 大小 12.3 KB · 2026-11-20 10:31`，并在详情里提示"若确认无用可删除，删除不可恢复"。

## 4. 页面结构与交互（全部复用现有视觉）

```
MainActivity 右上角 ⋮  →  [导入] [备份(gone)] [设置] [关于] [管理]  ← 新增一行
                                          ↓
DataManagerActivity（标题：应用数据管理；toolbar 同 SettingsActivity）
  ├─ 顶部概览行：总占用 24.6 MB · 文件 137 · 数据库 1
  ├─ 分组列表（每行 = app_recycler_item_filepicker，图标按类型映射已有 drawable）
  │    📁 files/          18 个封面 · 12.4 MB          >
  │    📁 databases/      yespdf.db · 1.8 MB           >
  │    📁 shared_prefs/   1 个设置文件 · 4 KB           >
  │    📁 cache/          可清空 · 6.2 MB              >
  └─ 进入子目录：沿用 ViewAllFragment 的"路径面包屑 + 列表"体验（可点上级返回）

点击条目 → BottomSheet（复用 createDeleteDialog 风格，改为操作菜单）：
   查看信息 / 重命名 / 删除 / 导出副本 / 复制路径
   （数据库：查看表结构 / 导出 JSON；文本：查看内容；封面：替换封面）
```

图标映射（全部已存在）：目录 `app_ic_folder_yellow_24dp`、jpg `app_ic_image_white_24dp`、db `app_ic_pdf_red_24dp`、xml `app_ic_action_settings_black`、未知 `app_img_file`。

## 5. 操作能力与安全边界（"尽量开放"但要有底线）

| 操作 | 封面 jpg | DB | SP xml | 其它 |
|---|---|---|---|---|
| 查看信息（路径/大小/时间/注释） | ✅ | ✅ | ✅ | ✅ |
| 重命名 | ✅（同步更新 `PDF.cover` 字段） | ❌ 禁用（改名即打不开） | ❌ 禁用 | ✅ |
| 删除 | ✅（`PDF.cover` 置空，列表回落到占位图） | ⚠️ 高危：二次确认 + 建议先导出 | ✅（= 恢复默认设置，需重启生效） | ✅ |
| 替换/导入内容 | ✅（从外部选图覆盖，自动压到 ≤512px/≤2MB） | ❌ | ❌ | ✅ 文本可覆盖 |
| 查看内容 | ✅ 预览缩略图 | ✅ 只读表/行数 + 按书名搜索，导出 JSON/CSV | ✅ 只读文本视图 | ✅ 文本/十六进制前 4KB |
| 导出副本 | ✅ | ✅ | ✅ | ✅ |

**明确不做**（并写进页面提示）：
- 不对 DB 做原地编辑（会绕过 GreenDAO 缓存与事务，极易损坏）；只读浏览 + 导出。
- 不对 SP XML 做原地文本编辑（损坏后 `SPStaticUtils` 直接崩）；提供"恢复默认"。
- 不提供"新建目录"（当前没有业务收益）。

## 6. 首次进入的专业性提示

文案（复用 `createDoubleBtnDialog`，左"以后再说"右"我知道了"）：

> **应用数据管理**
> 这里展示的是应用私有目录里的真实文件：书库数据库、封面缩略图、应用设置与缓存。
> 删除或改名可能造成**阅读进度、书签、封面丢失**，数据库文件尤其危险。
> 建议先"导出副本"再操作。此目录仅本应用可读写，不影响你的原始 PDF。

勾选/点击「我知道了」后写入 `SP_DATA_MANAGER_TIP_SHOWN`，之后不再打扰；页面右上角保留 `?` 图标可再次查看。

## 7. 技术细节（实现时按此执行）

1. **入口**：`app_pw_main.xml` 追加 `app_tv_manage`（图标 `app_ic_action_settings_black` 或 `app_ic_folder_yellow_24dp`，文案"管理"）；`MainActivity.pwMenu` 里加 `setOnClickListener { DataManagerActivity.start(this); dismiss() }`。
2. **新文件**（3 个）：
   - `app/src/main/java/com/aaron/yespdf/data/DataManagerActivity.kt`（~250 行）
   - `.../data/DataFileAdapter.kt`（~120 行）
   - `.../data/InternalDataMap.kt`（~60 行，注释表 + 类型识别）
   - 布局 `app_activity_data_manager.xml`（toolbar + 概览 TextView + RecyclerView，30 行）
3. **扫描**：`Dispatchers.IO` 递归 `listFiles()`，计算 `length()` 求和；只做一层懒加载（进入子目录再扫），避免上千文件卡顿。
4. **导出**：`FileProvider.getUriForFile(...)` + `Intent.ACTION_SEND`（`application/octet-stream`）→ 用户自选目标（微信/文件管理器/网盘）。需要给 `app_file_paths.xml` 补：
   ```xml
   <files-path name="app_files" path="." />
   <cache-path name="app_cache" path="." />
   <external-files-path name="app_ext" path="." />
   ```
   （不加 `<root-path>`，避免把私有数据暴露给任意被授权应用。）
5. **DB 只读浏览**：`SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)` 取 `sqlite_master` 列表与 `count(*)`；**不复用 GreenDAO session**，避免干扰写事务。
6. **危险操作**：删除 DB 前 `AlertDialog` 二次确认 + 提示"应用将自动重建空库"；删除后需 `DBHelper` 重连（或提示重启应用）。
7. **与 F2 联动**：F2 的 `PDF.pwdBlob` 写入后，DB 页可显示"已保存密码条目 N 个"（不显示明文）。

## 8. 工作量与顺序

| 步骤 | 内容 | 预估 |
|---|---|---|
| S1 | 入口 + 概览 + 目录列表 + 注释表 | 1 个提交 |
| S2 | 文件操作：查看信息/重命名/删除/复制路径 | 1 个提交 |
| S3 | 导出副本（FileProvider ± 路径声明） | 1 个提交 |
| S4 | DB 只读浏览 + 导出 JSON；SP 只读文本 + 恢复默认 | 1 个提交 |
| S5 | 封面替换（导入压缩复用 M1 的 ≤512px/≤2MB 规则） | 与 F4 合并 |

**结论：可以在不引入任何新依赖、不动数据库结构的前提下，用一个 Activity + 一个 Adapter + 一张注释表完成**，风险集中在"删除/改名"两类操作，用二次确认与上限约束即可控制。
