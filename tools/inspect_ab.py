#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
解析 adb backup 生成的 .ab 文件并输出数据检查报告。

用法：
    python tools/inspect_ab.py [可选的 .ab 路径]

要点（踩坑记录）：
- .ab 头 4 行是文本：ANDROID BACKUP / 格式版本 / 是否压缩 / 加密方式
- 压缩体是 **zlib deflate**（魔数 78 xx），**不是 gzip**（1f 8b）
  → 直接用 gzip 解会抛 BadGzipFile: Not a gzipped file (b'x\\xda')
- tar 必须流式读（r|），因为 zlib 流不可 seek
- 只解出 databases/ 与 shared_prefs/；files/ 下的封面仅统计，避免占盘
- sqlite 以 mode=ro 打开，绝不修改备份内容
"""
import io
import os
import re
import sqlite3
import sys
import tarfile
import zlib
import collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_AB = os.path.join(ROOT, "backup", "yespdf-official-v2.2.5.ab")
AB = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_AB
OUT = os.path.join(ROOT, "backup", "extracted")
REPORT = os.path.join(ROOT, "backup", "report.md")

PKG = "com.aaron.yespdf"
# 当前代码里 PDF 实体的列（GreenDAO 命名：驼峰 → 下划线大写，主键为 _id）
EXPECTED_PDF_COLUMNS = {
    "_id", "PATH", "DIR", "NAME", "COVER", "PROGRESS", "CUR_PAGE",
    "TOTAL_PAGE", "BOOKMARK", "LATEST_READ", "POSITION", "SCALE_FACTOR",
}
EXPECTED_TABLES = {"PDF", "COLLECTION", "RECENT_PDF"}
CODE_SCHEMA_VERSION = 3

lines = []


def p(text=""):
    print(text)
    lines.append(text)


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "{:.1f} {}".format(n, unit)
        n /= 1024.0


class DeflateStream(io.RawIOBase):
    """.ab 的压缩体是 zlib 流（Java DeflaterOutputStream），需要边读边解。"""

    def __init__(self, fp, wbits=zlib.MAX_WBITS):
        self._fp = fp
        self._decomp = zlib.decompressobj(wbits)
        self._buf = b""
        self._done = False

    def readable(self):
        return True

    def readinto(self, b):
        while not self._buf:
            if self._done:
                return 0
            chunk = self._fp.read(1 << 16)
            if chunk:
                self._buf = self._decomp.decompress(chunk)
            else:
                self._buf = self._decomp.flush()
                self._done = True
        n = min(len(b), len(self._buf))
        b[:n] = self._buf[:n]
        self._buf = self._buf[n:]
        return n


def open_ab(path):
    fp = open(path, "rb")
    magic = fp.readline().decode("ascii", "replace").strip()
    version = fp.readline().decode("ascii", "replace").strip()
    compressed = fp.readline().decode("ascii", "replace").strip()
    encryption = fp.readline().decode("ascii", "replace").strip()
    if magic != "ANDROID BACKUP":
        raise SystemExit("不是 Android backup 文件: {!r}".format(magic))
    if encryption not in ("none", ""):
        raise SystemExit("备份已加密 (encrypt={})，需要密码才能解".format(encryption))

    if compressed == "1":
        stream = io.BufferedReader(DeflateStream(fp), 1 << 16)
    else:
        stream = io.BufferedReader(fp)
    return version, compressed, encryption, stream


def main():
    if not os.path.exists(AB):
        raise SystemExit("找不到备份文件: {}".format(AB))

    version, compressed, encryption, stream = open_ab(AB)

    p("# YESPDF 备份数据检查报告")
    p()
    p("## 1. 备份文件")
    p()
    p("| 项 | 值 |")
    p("|---|---|")
    p("| 文件 | `{}` |".format(os.path.relpath(AB, ROOT).replace("\\", "/")))
    p("| 大小 | {} |".format(human(os.path.getsize(AB))))
    p("| 备份格式版本 | {} |".format(version))
    p("| 压缩 | {} |".format("zlib deflate" if compressed == "1" else "无"))
    p("| 加密 | {} |".format(encryption or "none"))
    p("| 备份范围 | 仅 `{}`（命令指定包名，无 -all） |".format(PKG))
    p()

    entries = []
    manifest = None
    dumps = {}
    cover_names = set()
    cover_bytes = 0
    total_bytes = 0

    with tarfile.open(fileobj=stream, mode="r|") as tar:
        for member in tar:
            name = member.name
            entries.append((name, member.size))
            total_bytes += member.size
            base = name.split("/")[-1]
            if base == "_manifest":
                manifest = tar.extractfile(member).read()
            elif "/db/" in name or "/sp/" in name:
                dumps[name] = tar.extractfile(member).read()
            elif "/f/" in name:
                cover_names.add(name.split("/f/")[-1])
                cover_bytes += member.size

    p("## 2. 内容概况")
    p()
    p("| 项 | 值 |")
    p("|---|---|")
    p("| tar 条目数 | {} |".format(len(entries)))
    p("| 解压后总大小 | {} |".format(human(total_bytes)))
    p("| `files/` 文件数（多为封面） | {}（{}） |".format(len(cover_names), human(cover_bytes)))
    p("| `db/` + `sp/` 条目 | {} |".format(len(dumps)))
    p()

    if manifest:
        text = manifest.decode("utf-8", "replace").splitlines()
        labels = ["格式版本", "包名", "应用 versionCode", "平台 versionCode",
                  "安装器", "是否含 APK", "签名个数", "签名[0]"]
        p("## 3. `_manifest`")
        p()
        p("| 字段 | 值 |")
        p("|---|---|")
        for i, ln in enumerate(text[:8]):
            p("| {} | `{}` |".format(labels[i] if i < len(labels) else "line{}".format(i),
                                     ln.strip()[:120]))
        p()
        pkg_ok = len(text) > 1 and text[1].strip() == PKG
        p("- 包名匹配：**{}**".format("✅ 与我们分支相同" if pkg_ok else "❌ 不是 " + PKG))
        p("- 签名来自原开发者 → `adb restore` 必被拒，我们走手动写入，不受影响")
        p()
    else:
        p("## 3. `_manifest`")
        p()
        p("⚠️ 未找到")
        p()

    os.makedirs(OUT, exist_ok=True)
    extracted = []
    for name, data in dumps.items():
        rel = name.split(PKG + "/", 1)[-1] if PKG + "/" in name else name
        rel = rel.lstrip("/")
        dst = os.path.join(OUT, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as f:
            f.write(data)
        extracted.append((rel, len(data)))

    p("## 4. 已解出（`backup/extracted/`）")
    p()
    p("| 文件 | 大小 |")
    p("|---|---|")
    for rel, size in sorted(extracted):
        p("| `{}` | {} |".format(rel, human(size)))
    p()

    db_path = None
    for rel, _ in extracted:
        if rel.endswith("yespdf.db"):
            db_path = os.path.join(OUT, rel.replace("/", os.sep))
    if not db_path:
        for root, _dirs, files in os.walk(OUT):
            if "yespdf.db" in files:
                db_path = os.path.join(root, "yespdf.db")
                break

    integrity = None
    user_version = None
    cols = []
    tables = []

    p("## 5. 数据库（兼容性核心）")
    p()
    if not db_path or not os.path.exists(db_path):
        p("❌ 未找到 yespdf.db —— 没有书库数据，迁移无意义")
        p()
    else:
        con = sqlite3.connect("file:{}?mode=ro".format(db_path.replace("\\", "/")), uri=True)
        cur = con.cursor()
        integrity = cur.execute("PRAGMA integrity_check").fetchone()[0]
        user_version = cur.execute("PRAGMA user_version").fetchone()[0]
        tables = [r[0] for r in cur.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
        counts = {}
        for t in tables:
            try:
                counts[t] = cur.execute("SELECT COUNT(*) FROM {}".format(t)).fetchone()[0]
            except Exception:
                counts[t] = -1

        p("| 检查项 | 实测 | 判定 |")
        p("|---|---|---|")
        p("| `integrity_check` | `{}` | {} |".format(
            integrity, "✅ 完好" if integrity == "ok" else "❌ 文件损坏"))
        p("| `user_version`（schemaVersion） | `{}` | {} |".format(
            user_version,
            "✅ 与我们一致(3)，可直接读取" if user_version == CODE_SCHEMA_VERSION
            else ("⚠️ 低于 3：首次启动会触发 GreenDAO 迁移（数据保留）"
                  if user_version < CODE_SCHEMA_VERSION
                  else "❌ 高于 3：我们的代码会因降级而崩，需先处理")))
        missing_tables = EXPECTED_TABLES - set(tables)
        p("| 表齐备 | `{}` | {} |".format(
            "`, `".join(tables),
            "✅" if not missing_tables else "❌ 缺 " + "、".join(sorted(missing_tables))))
        p()

        p("### 行数")
        p()
        p("| 表 | 行数 |")
        p("|---|---|")
        for t, c in sorted(counts.items()):
            p("| {} | {} |".format(t, c))
        p()

        if "PDF" in tables:
            cols = [r[1] for r in cur.execute("PRAGMA table_info(PDF)")]
            missing = EXPECTED_PDF_COLUMNS - set(cols)
            extra = set(cols) - EXPECTED_PDF_COLUMNS
            p("### `PDF` 表字段兼容性")
            p()
            p("备份中的字段（{} 个）：`{}`".format(len(cols), "`, `".join(cols)))
            p()
            p("- 我们代码需要但缺失的字段：**{}**".format(
                "无 ✅" if not missing else "、".join(sorted(missing)) + " ❌ 会报 no such column"))
            p("- 备份中多出的字段（GreenDAO 用显式列查询，多余列无害）：**{}**".format(
                "无" if not extra else "、".join(sorted(extra))))
            p()

            p("### 书库抽样")
            p()
            rows = list(cur.execute(
                "SELECT NAME, CUR_PAGE, TOTAL_PAGE, PROGRESS, PATH FROM PDF LIMIT 5"))
            p("| 书名 | 页 | 进度 | 原文件路径 |")
            p("|---|---|---|---|")
            for r in rows:
                p("| {} | {}/{} | {} | `{}` |".format(r[0], r[1], r[2], r[3], (r[4] or "")[:60]))
            p()

            cover_refs = {os.path.basename(c) for (c,) in cur.execute(
                "SELECT COVER FROM PDF WHERE COVER IS NOT NULL AND COVER <> ''")}
            matched = cover_refs & cover_names
            p("### 封面匹配")
            p()
            p("- DB 引用封面 {} 个；备份 `files/` 中 {} 个".format(len(cover_refs), len(cover_names)))
            p("- **能对上 {} 个**（{}）".format(
                len(matched),
                "✅ 完整" if cover_refs and len(matched) == len(cover_refs)
                else "⚠️ 缺 {} 个，缺的会显示占位图".format(len(cover_refs) - len(matched))))
            p()

            paths = [x for (x,) in cur.execute("SELECT PATH FROM PDF") if x]
            roots = collections.Counter(os.path.dirname(x) for x in paths)
            p("### 原 PDF 目录分布（迁移后需确认文件仍在设备）")
            p()
            p("| 目录 | 数量 |")
            p("|---|---|")
            for d, c in roots.most_common(8):
                p("| `{}` | {} |".format(d, c))
            p()

        p("### 建表语句（用于与当前代码比对）")
        p()
        p("```sql")
        for t in tables:
            row = cur.execute(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (t,)).fetchone()
            if row and row[0]:
                p(row[0].replace("\n", " ")[:400])
        p("```")
        p()
        con.close()

    p("## 6. SharedPreferences")
    p()
    sp_files = [rel for rel, _ in extracted if "/sp/" in "/" + rel or rel.startswith("sp/")]
    if not sp_files:
        p("⚠️ 未发现 SP 文件（官方版可能没写过设置）")
    else:
        for rel in sp_files:
            p("- `{}`".format(rel))
            path = os.path.join(OUT, rel.replace("/", os.sep))
            try:
                text = open(path, encoding="utf-8", errors="replace").read()
                keys = re.findall(r'name="([^"]+)"', text)
                p("  - 键 {} 个：`{}`".format(len(keys), "`, `".join(keys[:30])))
            except Exception as exc:
                p("  - 读取失败: {}".format(exc))
        p()
        p("> 迁移时按目录整体搬运 `sp/*.xml` → `shared_prefs/`，不假设文件名；值不打印。")
    p()

    p("## 7. 结论与兼容判定")
    p()
    if db_path and integrity is not None:
        verdict = []
        verdict.append("✅ 数据库完整" if integrity == "ok" else "❌ 数据库损坏，先别迁")
        if user_version == CODE_SCHEMA_VERSION:
            verdict.append("✅ schemaVersion 一致（3）→ 新包可直接读取")
        elif user_version < CODE_SCHEMA_VERSION:
            verdict.append("⚠️ schemaVersion={} 低于 3 → 首次启动会迁移（数据保留，建议先留 .ab）".format(user_version))
        else:
            verdict.append("❌ schemaVersion={} 高于 3 → 需在代码侧处理，不能直接写".format(user_version))
        if cols:
            missing = EXPECTED_PDF_COLUMNS - set(cols)
            verdict.append("✅ 实体所需字段全部存在" if not missing
                           else "❌ 缺字段：{}".format("、".join(sorted(missing))))
        for v in verdict:
            p("- " + v)
    else:
        p("- ❌ 没有可分析的数据库")

    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n报告已写入:", REPORT)


if __name__ == "__main__":
    main()
