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
| 删除 | ✅（`PDF.cover` 置空，列表回落到占位图） | ⚠️ **专用高危流程**（见 §9.1） | ✅（= 恢复默认设置，需重启生效） | ✅ |
| 替换/导入内容 | ✅（从外部选图覆盖，自动压到 ≤512px/≤2MB） | ❌ | ❌ | ✅ 文本可覆盖 |
| 查看内容 | ✅ 预览缩略图 | ✅ 只读表/行数 + 按书名搜索，导出 JSON/CSV | ✅ 只读文本视图 | ✅ 文本/十六进制前 4KB |
| 导出副本 | ✅ | ✅ | ✅ | ✅ |
| **复制路径** | ✅ | ✅ | ✅ | ✅（`ClipboardUtils`，已有依赖） |
| **整目录复制进出** | — | ✅（见 §9.2） | ✅ | ✅ |

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
4. **导出**：按 §9.2 复制到 `Download/YESPDF_data/...`；单个文件也可 `FileProvider.getUriForFile(...)` + `ACTION_SEND` 分享出去。
   现有的 `app_file_paths.xml`（`<external-path name="yespdf" path="" />`）已覆盖 Download，**无需新增 `<files-path>` 或 `<root-path>`** ——
   这同时避免了把私有目录授权给任意第三方应用（比"打开目录"更安全，也是选择中转目录方案的主因）。
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

---

## 9. 决策记录（用户 2026-11-20 拍板）

### 9.1 数据库整体删除 = "专用函数 + 强制退出"

用户明确要求：**完全删掉时执行特定函数，警告这是危险操作；删除后下次启动会"部分恢复"（重建空库），大量数据永久丢失；确认后删除并立即退出应用，直到用户下次启动才重新初始化。**

实现（`DataManagerActivity` 内，而不是散在 UI 回调里）：

```kotlin
/**
 * 高危：删除书库数据库并立即退出进程。
 * 下次冷启动时 DBHelper.init() 会用 DaoMaster 重建空库（"部分恢复"），
 * 但阅读进度/书签/分组/已保存密码永久丢失，无法找回。
 */
private fun deleteDatabaseAndExit() {
    // 1) 趁进程还活着，先把能"留个念想"的东西导出一份（可选，见 §9.2）
    // 2) 关闭所有连接：DBHelper 重连前必须退出，否则 SQLite 仍持有已删除的 inode
    //    这里不做热重连，直接交给下一次冷启动
    deleteDatabase(AppConfig.DB_NAME)   // 同时删除 -wal / -shm / -journal
    // 3) 清理残留：封面等派生文件按用户选择保留或一并删除
    // 4) 立即退出，禁止后续任何写操作
    finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
    exitProcess(0)
}
```

确认交互（两级，全部复用现有 dialog）：

1. 第一级 `createDoubleBtnDialog`：
   > **删除整个书库数据库？**
   > 这会删除书名索引、阅读进度、书签、分组以及已保存的密码。
   > 你的**原始 PDF 文件不会被删除**。删除后应用会立即退出，下次启动会重建一个空书库 —— 届时这些数据将**永久无法恢复**。
2. 第二级 `createDeleteDialog`（现有勾选框样式）：
   - 勾选项 1：`同时删除封面缩略图`（默认勾选）
   - 勾选项 2：`我已导出备份`（不勾选则按钮禁用，除非用户勾"我确认不需要备份"）
3. 确认后走 `deleteDatabaseAndExit()`。

关键点：**不热重连 DB**。GreenDAO 的 `DaoSession`/`SQLiteDatabase` 已打开的连接在文件被删后仍指向旧 inode，热重连极易出现"写入落到幽灵文件"的问题，直接退出是唯一干净的做法。

### 9.2 目录"复制进出"（零新增依赖）

**不引入 `androidx.documentfile`**。`DocumentsContract` 的 tree 遍历 API 是 framework 自带的，手写约 40 行即可：

```kotlin
// 递归枚举用户选中的目录（零依赖，替代 DocumentFile）
val treeUri = data.data!!
contentResolver.takePersistableUriPermission(treeUri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
contentResolver.query(
    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId),
    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED),
    null, null, null)
// 读：contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
// 写：contentResolver.openOutputStream(...)   ← 导出方向需要它
```

两个方向：

| 方向 | 首选方式 | 兜底方式 |
|---|---|---|
| **复制出** | 多选 →「导出」→ `ACTION_OPEN_DOCUMENT_TREE` 让用户挑目标文件夹 → 逐个 `openOutputStream` 写入，保留原文件名 | 不选目标时直接写 `Download/YESPDF_data/<时间戳>/` + `manifest.txt` |
| **复制进** | 「从文件夹导入」→ `ACTION_OPEN_DOCUMENT_TREE`；或「选择文件导入」→ `ACTION_OPEN_DOCUMENT`（可多选，侧栏更全，见 §10） | 读取 `Download/YESPDF_restore/` |

导入的安全约束：

- **数据库文件导入后同样强制退出**（与 §9.1 同一条路径），提示"导入将在下次启动生效"，避免连接错乱；
- SP 导入后提示"设置将在重启后生效"；
- 覆盖前统一 `createDoubleBtnDialog` 确认；导出时把被覆盖文件写进目标目录的 `_before_replace_<时间>/`（进出互为保险）。
- 所有复制都在 `Dispatchers.IO` 上带进度回调，超过 50MB 或文件数 > 500 时先估算并提示预计耗时。

### 9.3 已确认的 UI 细节

- 入口文案「**管理**」，图标复用 `app_ic_folder_yellow_24dp`，位置在 `app_pw_main.xml` 的「设置」与「关于」之间（即「关于」上方）。
- 条目操作菜单包含「**复制路径**」（`ClipboardUtils.copyText`，blankj 已提供，零新增依赖）。

---

## 10. 调用系统「文件」App 的调研结论（用户观察到两种侧边栏）

| 用户看到的结果 | 实际对应的 Intent | 侧边栏内容 | 能否选文件夹 |
|---|---|---|---|
| ① 图片 / 视频 / 音频 / 下载 / 手机型号 / 某些 App 开放的目录 | `ACTION_OPEN_DOCUMENT`（**未设 `EXTRA_MIME_TYPES`**，`type="*/*"`；或 `ACTION_GET_CONTENT`） | **完整**：`MediaDocumentsProvider` 的媒体分类根 + `DownloadStorageProvider` + `ExternalStorageProvider`（手机型号）+ 第三方 `DocumentsProvider` | ❌ 只能选文件 |
| ② 只有 下载 / 手机型号 | `ACTION_OPEN_DOCUMENT_TREE`（目录选择器） | **只有可创建子目录的 roots**：Downloads、各存储卷、以及声明了 `FLAG_DIR_SUPPORTS_CREATE` 的第三方目录。媒体分类根不提供 createDocument，故不出现 | ✅ 可选文件夹 |

**两个决定性事实**：

1. **侧边栏被裁剪的第一元凶是 `EXTRA_MIME_TYPES`**：一旦按类型过滤，DocumentsUI 会自动把侧栏收缩成"可能含该类型的入口"，"最近/下载/SD 卡"都会消失。
   → 所以**永远不要设 `EXTRA_MIME_TYPES`**；要限制类型就在拿到 `content://` 之后自己校验 MIME（我们本来就要校验：只接受 `.jpg`/`.xml`/`.db`）。
2. **"选文件夹"与"完整侧栏（含图片/视频/音频）"在系统 Picker 里不可兼得**：这是 DocumentsUI 的 root 过滤规则决定的，不是我们调用方式错了。

**零依赖的最终方案：双入口**

| 入口 | Intent 配置 | 命中用户诉求 |
|---|---|---|
| 「选择文件导入」 | `ACTION_OPEN_DOCUMENT` + `type="*/*"` + `EXTRA_ALLOW_MULTIPLE=true` + `CATEGORY_OPENABLE`，**不设** `EXTRA_MIME_TYPES` | **结果 ①**（完整侧栏），可多选文件 |
| 「从文件夹导入」 | `ACTION_OPEN_DOCUMENT_TREE` + `FLAG_GRANT_READ/WRITE/PERSISTABLE_URI_PERMISSION` | **结果 ②**（可选文件夹） |

补充两个坑（写进 UI 提示）：

- **Android 11+ 对 `OPEN_DOCUMENT_TREE` 有硬限制**：不能直接选 `Download` 根、内部存储根、`Android/data`、`Android/obb`，否则系统提示"无法使用此文件夹"。
  → 页面提示引导用户选 **`Download/YESPDF_restore`** 这类子目录；这也是保留固定中转目录兜底的原因。
- 不同厂商"文件"App（DocumentsUI 定制版）表现可能不同；若某项不可用，退回到兜底的中转目录路径，功能不会断。

## 11. 多选 / 复制 / 粘贴（复用"导入"页的现有多选 UI）

用户要求复制出时支持多选，因此文件管理页引入"选择模式"，**完全复用 `SelectActivity` / `ScanActivity` 已有的多选控件**：

| 复用项 | 现有位置 | 在文件管理页的用途 |
|---|---|---|
| `app_sel_select_single` 复选框样式 | `ViewAllAdapter` 给 `app_cb` 动态装配的状态选择器 | 列表条目的多选勾选框（相同外观） |
| `app_ibtn_check`（全选图标 + 三态 `app_ic_action_selectall_*`） | `SelectActivity.ibtnSelectAll` | 顶部全选/取消全选 |
| `app_include_operation_bar.xml`（底部操作栏 + 计数按钮 + `app_sel_operate_*`） | `MainActivity` 批量删除 | 底部条：「复制 N」「导出 N」「删除 N」 |
| 长按进入选择态、`invalidateOptionsMenu` 切换菜单 | `AllFragment2` / `CollectionFragment2` | 同一套交互习惯 |

粘贴模型（应用内剪贴板，比系统剪贴板更可控）：

```
长按/勾选 → 底部「复制」 → 记录 List<源绝对路径> + 操作类型(COPY/MOVE) 到 AppClipboard（内存单例）
   ↓ 进入目标目录
目标目录底部条出现「粘贴 N」→ 逐项复制（同名时按 `name (1).jpg` 规则去重，弹窗可改）
```

- 「复制」与「剪切」两种语义都提供（`MOVE` 走 copy+delete，删除前二次确认）；
- 跨"内部目录 ↔ 系统文件夹"的搬运走 §10 的两个系统入口，不经过 AppClipboard；
- 选择态期间禁用列表刷新（AOSP DocumentsUI 的 `ContentLock` 思路），避免选中项失效。
