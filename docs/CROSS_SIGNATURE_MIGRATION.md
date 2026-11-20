# 跨签名数据转移方法论

> 场景：旧数据来自**另一把签名**的包（原作者版 / debug 版），要迁到本项目的正式签名版。
> 关键词：`adb restore` 会被签名校验拒绝，但**数据本身可以用文件级搬运**。

## 1. 结论速查

```
能 root？
├─ 能 ──► 路径 B'：su + cp/tar（注意 chown + restorecon）
└─ 不能 ──► 旧包是 debuggable？
    ├─ 是 ──► 路径 B：run-as 读写（最干净，属主/上下文天然正确）
    └─ 否 ──► 选一个：
        ├─ 路径 A：应用内「导出 / 从备份自动恢复」（零特权，推荐给普通用户）
        ├─ 路径 C：重签名 .ab + adb restore（可一次搬完 db+sp+封面，需重建 tar）
        └─ 路径 B''：装一个「正式签名 + debuggable」迁移包当跳板，用完覆盖回正式包
```

**永远不要**指望直接 `adb restore`：见下一节。

## 2. 为什么 `adb restore` 会被拒（源码依据）

`adb restore` 走 full-stream 恢复，`FullRestoreEngine` 读 manifest 时立刻定策略：
`TarBackupReader.chooseRestorePolicy()` →

```java
if (signatures == null) return RestorePolicy.IGNORE;
...
// Verify signatures against any installed version;
// if they don't match, then we fall though and ignore the data.
if (eligibilityRules.signaturesMatch(signatures, pkgInfo)) { ...ACCEPT... } else { IGNORE }
```

`BackupEligibilityRules.signaturesMatch()`：系统分区应用跳过校验，其余要求签名一致
（支持密钥轮换历史）。**不匹配 → 数据被静默丢弃，命令仍返回成功**。

以下都绕不过去：`-noapk`（签名仍写在 manifest 里）、`restoreAnyVersion`（只放宽版本）、
降级安装备份内旧 APK（签名不同装不上）。

## 3. 三条路径对比

| 路径 | 前提 | 能搬什么 | 操作方 | 风险 |
|---|---|---|---|---|
| **A. 应用内导出/导入** | 无（App 自己有权限） | db、sp、封面（逐个或整目录） | 用户点几下 | 低（但要求目标目录正确） |
| **B. run-as 手动写入** | 目标包 `debuggable=true` | 任意私有文件 | 脚本 + adb | 低（写入前须 force-stop） |
| **C. 重签名 `.ab`** | 只需目标包已安装 | db + sp + 封面一次搬完 | 脚本 + adb restore | 中（要重建 tar，长名/编码要合规） |

## 4. 路径 A：应用内（本项目的实现）

**关键：目标目录必须是"文件该在的地方"，不是"备份所在的地方"。**

| 文件 | 目标目录 |
|---|---|
| `yespdf.db` | `/data/data/<pkg>/databases/` |
| `spUtils.xml` 等设置 | `/data/data/<pkg>/shared_prefs/` |
| 封面 `*.jpg` | `/data/data/<pkg>/`（dataDir 根，因 `PDF.COVER` 存的是绝对路径） |

本项目提供两条入口：

- **按当前目录导入**：进入目标目录 → ⋮ → 从系统导入文件夹/文件 → 复制到当前目录
- **从备份自动恢复（推荐）**：⋮ → 从备份自动恢复 → 选备份根目录，应用递归扫描后
  按上述规则自动归位；覆盖前把旧文件存成 `xxx.bak-<时间戳>`

> 恢复数据库后**必须重启应用**：`DataManager` 只在 `App.onCreate` 加载一次。

### 前置：让系统"文件"App 看得到你 push 的文件

`adb push` 不触发媒体扫描，DocumentsUI 走 MediaStore 索引，`.db/.xml` 会显示为空目录：

```bash
adb shell content call --uri content://media --method scan_volume --arg external_primary
adb shell "content query --uri content://media/external/downloads \
  --projection _display_name --where \"_data LIKE '%YESPDF_restore%'\""
```

## 5. 路径 B：unpack + 手动写入

```bash
adb shell am force-stop <pkg>                      # 必须，否则 SQLite 会回写旧数据
adb push yespdf.db /data/local/tmp/
adb shell "run-as <pkg> sh -c \
  'rm -f databases/yespdf.db-wal databases/yespdf.db-shm; \
   cat /data/local/tmp/yespdf.db > databases/yespdf.db'"
adb shell am start -n <pkg>/.main.MainActivity
```

- `run-as` 只要求 `debuggable`，**不校验签名**，且以 app uid 写入 → 属主与 SELinux 上下文天然正确
- 非 debuggable 会报 `run-as: package not debuggable`
- 若选"迁移包跳板"：正式密钥 + `debuggable true` + 同 versionCode，写完数据后用正式包覆盖
  （同签名，数据保留，`debuggable` 随之关闭）

## 6. 路径 C：重签名 `.ab`

原理：`manifest` 是一个 8 行文本条目，签名只占最后两行：

```
1                 ← 格式版本
<包名>
<应用 versionCode>
<平台 versionCode>
<安装器>
0                 ← hasApk
1                 ← 签名个数
<证书 DER 的 hex> ← 换成目标包的签名
```

步骤：

1. 对**目标包**做一次 `adb backup -noapk`，取出它 manifest 里的签名行
2. 用原备份的 manifest 前 6 行 + 目标包的签名两行，拼出新 manifest
3. 重建 tar（manifest 变长会改变后续偏移，**不能原地补零**）→ zlib 压缩 → 拼回头部
4. `adb restore crafted.ab`

合规要点（否则 RestoreEngine 解析失败）：

- tar 用 **USTAR**（`name≤100`、`prefix≤155`、合计 `≤255` 字节）；Android 的 reader 不认 PAX/GNU 长名头
- 压缩体是 **zlib deflate（`78 xx`）**，不是 gzip（`1f 8b`）
- 头部 4 行：`ANDROID BACKUP` / 格式版本 / `1`(压缩) / `none`(未加密)
- manifest 以 `\n` 结尾

## 7. 实战检查清单（本项目踩过的坑）

| # | 坑 | 判据 / 处置 |
|---|---|---|
| 1 | **导入"成功"但书库为空** | 查 `databases/yespdf.db` 的行数；常见是文件被复制进 `files/db/` |
| 2 | 系统文件 App 里目录是空的 | 媒体库未索引 → `content call ... scan_volume` |
| 3 | 应用装进了访客空间 | `dumpsys package` 看 `User 0/10`；`pm install-existing --user 0 <pkg>` |
| 4 | 启动报 `Error type 3` | 同上，组件在另一用户空间 |
| 5 | `schemaVersion` 高于代码 | 打开时因"降级"崩溃 → 提升代码 `greendaoschemaVersion` 对齐，或 PC 侧改 `PRAGMA user_version` |
| 6 | `PDF` 表多出未知列 | GreenDAO 用显式列查询，多余列无害 |
| 7 | 封面全成占位图 | 封面在 dataDir **根**（`r/` 域），不是 `files/`；且文件名 = `PDF.NAME + ".jpg"` |
| 8 | 封面"是 jpg 实为 PNG" | 上游用 `CompressFormat.PNG` 存 `.jpg` 名；按内容判断格式 |
| 9 | 密码解不开 | Keystore 密钥不出设备/不随备份迁移 → 密文失效属正常，应静默清除并重新询问 |
| 10 | 粘贴覆盖后不生效 | 自动改名成 `xxx (1).db` → 改为"同名先备份再覆盖" |
| 11 | 迁移后不重启看不到变化 | `DataManager` 只在 `App.onCreate` 加载一次 |
| 12 | 原 PDF 路径失效 | 书库能显示但打不开；迁移前抽查 `adb shell ls -l "<PATH>"` |

## 8. 工具索引

| 脚本 | 用途 |
|---|---|
| `tools/inspect_ab.py` | 解 `.ab` → 检查 `_manifest`/DB `user_version`/表行数/字段兼容/封面匹配率 |
| `tools/inspect_ab_v2.py` | 只读分析 `.ab` 体积构成（哪个 domain 占大头、图片大小分布） |
| `tools/extract_covers.py` | 从 `.ab` 的 `r/` 域导出封面图片 |
| `tools/craft_ab.py`（规划中） | 重签名 `.ab`：替换 manifest 签名行 + 重建 USTAR tar |

> 所有脚本都是零第三方依赖（zlib/tarfile/sqlite3 标准库）。
