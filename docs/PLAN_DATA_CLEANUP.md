# 数据形态治理计划（P1–P4）

> 目标：**彻底抛弃旧封面格式**（PNG 冒充 jpg、散落在 dataDir 根、平均 979KB），
> 让私有目录结构清晰、可一键整理、可一键瘦身；同时修掉管理页的两处体验问题。

## 0. 目标形态

```
/data/user/0/com.aaron.yespdf/
├── databases/yespdf.db                   书库（唯一数据源）
├── shared_prefs/spUtils.xml              设置/规则/密码密文
├── files/
│   └── covers/<书名>.jpg                 ← 新位置，≤512px JPEG q80，≤2MB
├── cache/                                Glide 磁盘缓存（可清）
└── （dataDir 根不再保留任何图片）
```

`PDF.COVER` 统一写 `files/covers/<书名>.jpg`。

现状问题（实测）：165 张封面共 157.8 MB 位于 dataDir 根；格式是 **PNG 但扩展名 .jpg**；
新代码已把"新生成"的封面改成 512px JPEG，但**写入路径没变**，所以旧格式一直存在。

## P1 三个"一键"操作（数据形态）

统一入口：`管理 → ⋮` 增加三项。全部在后台线程执行、带结果报告，可重复执行（幂等）。

### ① 一键清理旧封面（orphan）

| 项 | 设计 |
|---|---|
| 扫描范围 | dataDir 根 + `files/covers/` |
| 判定残留 | 文件 basename 不在 `PDF.COVER` 的 basename 集合里 |
| 动作 | 先列清单预览（数量/总大小）→ 确认后删除 |
| 安全 | 仅应用私有目录、仅常见图片扩展名、跳过仍被引用的文件 |
| 与 `CoverCleaner` 区别 | 后者是"删书后清封面"，本项是"全库对账清孤儿" |

### ② 一键压缩封面（满足要求则不压）

| 项 | 设计 |
|---|---|
| 遍历 | 仅遍历 `PDF.COVER` 引用的文件 |
| 合规判定（跳过条件） | 最长边 ≤512px **且** 体积 ≤200KB **且** 文件头为 JPEG（FFD8） |
| 不合规处理 | 解码 → 按最长边缩到 512 → JPEG q80 → 必要时逐级降质到 ≤2MB → 覆盖 |
| 特别处理 | PNG 冒充 `.jpg` 的，解码后统一存为**真 JPEG** |
| 报告 | 压缩前/后总大小、节省量、跳过数、失败数 |

### ③ 一键整理（reorganize，核心）

四个阶段，任一步失败可重试（幂等）：

1. **归位**：dataDir 根下被引用的封面 `renameTo(files/covers/<同名>)`；同名已存在则比较大小，保留较大者
2. **改库**：事务内批量把 `PDF.COVER` 前缀替换为 `files/covers/`
3. **清残**：识别误放目录（`files/db/*.db`、`files/sp/*.xml`）→ 提示删除；若 `databases/yespdf.db` 缺失则可选择采用它们
4. **收尾**（可选勾选）：自动执行 ①（清孤儿）与 ②（压不合规）

建议附带：整理前把 `yespdf.db` 导出一份到 `Download/YESPDF_backup/`（可选开关），出事可回退。

### ④ 代码侧配套（"抛弃旧格式"的真正落点）

- `DBHelper` 生成封面路径改为 `filesDir/covers/<name>.jpg`（写前 mkdirs）
- 新增 `CoverPaths.resolve(cover)`：DB 里的路径不存在时，按 basename 回退查找 `files/covers/` 与 dataDir 根
  → 保证"整理前/整理后/整理中断"三种状态都能显示封面
- `DataManager` 的封面列表同样走 `resolve`

### P1 验收

- 整理后 dataDir 根无图片；`files/covers/` 体积从 157.8MB 降到约 8–15MB
- 书库封面全部正常显示；`管理` 概览数字与磁盘一致
- 重复点"整理"不再有变化（幂等）
- 备份体积同步下降（adb backup 从 155MB → 个位数 MB）

## P2 多选态可见性

现状：选择态只显示右侧一个灰复选框，没有整行高亮，箭头消失后位置也无提示。

对齐"导入"页（`ViewAllAdapter` 用 `app_bg.isSelected` 做整行高亮）的做法：

| 改动 | 细节 |
|---|---|
| 整行高亮 | item 根布局加 `android:background="@drawable/app_sel_item_bg"`（`state_selected` → 浅红/浅灰底），绑定 `itemView.isSelected = checked` |
| 复选框位置 | 用 `FrameLayout` 把 `app_iv_next` 与 `app_cb` 叠在右侧同一位置，切换时不跳动 |
| 复选框样式 | 选择态用 `app_sel_main_select`（与导入页一致），尺寸 24dp |
| 辅助信息 | 底部栏已有"已选 N 项"；选中时 toolbar 标题同步（已有） |
| 目录可选中 | 已支持（上一版开放） |

## P3 目录列表去重与"少读写"

| 问题 | 方案 |
|---|---|
| `app data` 与 `cache/files/databases/shared_prefs` 是父子关系，列表重复 | 移除 `app data` 这个 root；旧封面以一个**扁平视图**呈现：标题「旧封面（dataDir 根）」，只列 dataDir 的直属文件，不递归子目录 |
| 概览统计重复遍历（`app data` 递归包含其余 root） | `loadOverview()` 只统计 `databases / shared_prefs / files / cache` 四个精确 root；旧封面单独计一行 |
| cache 是什么 | 列表 note 直接写「Glide 图片缓存，可清空」；点开可见 `image_manager_disk_cache` 等子项并注释 |
| 冗余副本 | 可选：限制/关闭 Glide 磁盘缓存（`DefaultOption` 若支持 `diskCacheStrategy`），因为原图就在本地且很小；先做"可见 + 可清"，再评估是否关闭 |

## P4 文案精简

目录行的 description 现状是长句（如"书库数据库：书名·路径·进度·书签·分组 · 5 项 · 88KB"），改为短标签：

| 目录 | 新 description |
|---|---|
| `files` | 封面图片 · N 项 · 大小 |
| `databases` | 书库数据库 · N 项 · 大小 |
| `shared_prefs` | 应用设置 · N 项 · 大小 |
| `cache` | 可清缓存 · N 项 · 大小 |
| 旧封面视图 | 旧封面 · N 张 · 大小 |

完整解释移到「查看信息」弹窗（`buildNote` 已支持多行）。

## 实施与风险

| 阶段 | 影响面 | 主要风险 | 缓解 |
|---|---|---|---|
| P1-④ 路径改造 | `DBHelper`、新封面写入 | 老库路径失效 | `CoverPaths.resolve` 双向兼容 |
| P1-③ 整理 | 文件移动 + DB 批量更新 157MB | 中断导致 COVER 悬空 | 幂等重试 + 整理前导出 DB |
| P1-② 压缩 | 高质量解码 165 张图，内存峰值 | OOM | 逐张处理、`inSampleSize` 预采样、最多 2 线程 |
| P2 | 布局与适配器 | 视觉回归 | 与导入页一致 |
| P3 | roots 结构与概览统计 | 少列目录 | 保留"旧封面"专项入口 |

**推荐顺序：P2（改动最小、立刻体感）→ P3（去重，顺便修 IO）→ P1（收益最大、需装机体感）→ P4（文案）**
或按收益：P1 → P2 → P3 → P4。

## 待确认

1. P1 的"整理"是否**默认勾选**自动压缩与清孤儿？（建议默认勾选）
2. Glide 磁盘缓存是否要**关闭**（关闭后封面加载略慢，但省一份副本）？
3. P1 整理前是否自动导出 DB 副本（建议开启）？

## 实施记录（P1-P4 已完成）

| 阶段 | 落地内容 | 关键文件 |
|---|---|---|
| P1 | 封面路径约定 + 三操作 | `common/CoverPaths.kt`、`data/CoverOps.kt`、`DBHelper`（写 `files/covers/`）、4 个 Adapter 用 `CoverPaths.resolvePath` |
| P2 | 选中可见性 | `drawable/app_sel_select_single.xml`（补三态）、`app_sel_item_bg.xml`、`app_shape_selected_bg.xml`、`app_recycler_item_filepicker.xml`（箭头与勾选框叠放）、`DataFileAdapter`（`itemView.isSelected`） |
| P3 | 目录去重 | `InternalData.dataRoot` 加 `filesOnly`；移除 `app data` 根，改「旧封面」专项视图；`loadOverview` 不再重复统计 |
| P4 | 文案 | `InternalData.roots()` 与 `noteOf()` 的目录说明改为短标签 |

### 架构阈值自查（arch_check）

| 指标 | 当前 | 上限 | 结论 |
|---|---|---|---|
| `common` 顶层源文件 | **24** | 25 | ⚠️ 仅剩 1 个余量，**后续新增必须放子目录** |
| `data` 源文件 | 5 | 25 | OK |
| `common/DataManagerActivity` 行数 | 991 | 1800 | OK（已把自动恢复/导入/封面维护拆成独立函数与 `CoverOps` 文件） |

依赖方向：`CoverPaths`（L0）只依赖 `Context`/`File`；`CoverOps`（L1）依赖 `common`（L0）；
4 个 Adapter（L2）依赖 `common`（L0）。无新增反向依赖，`arch_check` 无需新增 WAIVER。
