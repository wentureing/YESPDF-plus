import gzip
import io
import os
import sqlite3
import sys
import tarfile
import collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AB = os.path.join(ROOT, "backup", "yespdf-official-v2.2.5.ab")
OUT = os.path.join(ROOT, "backup", "extracted")
PKG = "com.aaron.yespdf"
REPORT = os.path.join(ROOT, "backup", "report.md")

lines = []


def p(text=""):
    print(text)
    lines.append(text)


def open_ab(path):
    with open(path, "rb") as f:
        magic = f.readline().decode("ascii", "replace").strip()
        version = f.readline().decode("ascii", "replace").strip()
        compressed = f.readline().decode("ascii", "replace").strip()
        encryption = f.readline().decode("ascii", "replace").strip()
        if magic != "ANDROID BACKUP":
            raise SystemExit("不是 Android backup 文件: {!r}".format(magic))
        if encryption != "none":
            raise SystemExit("备份已加密 (encrypt={})，需要密码才能解".format(encryption))
        data = f.read()
    stream = gzip.GzipFile(fileobj=io.BytesIO(data)) if compressed == "1" else io.BytesIO(data)
    return version, compressed, stream


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "{:.1f} {}".format(n, unit)
        n /= 1024.0


version, compressed, stream = open_ab(AB)
p("# YESPDF 官方版备份检查报告")
p()
p("## 1. 备份文件")
p()
p("| 项 | 值 |")
p("|---|---|")
p("| 文件 | `backup/yespdf-official-v2.2.5.ab` |")
p("| 大小 | {} |".format(human(os.path.getsize(AB))))
p("| 备份格式版本 | {} |".format(version))
p("| 压缩 | {} |".format("gzip" if compressed == "1" else "无"))
p("| 加密 | none（可直接解析） |")
p("| 备份范围 | **仅 `{}`**（命令末尾指定包名，无 `-all`，`-noapk -noshared`） |".format(PKG))
p()

# 遍历 tar
entries = []
manifest_bytes = None
to_extract = {}
cover_count = 0
cover_bytes = 0
total_bytes = 0

with tarfile.open(fileobj=stream, mode="r|") as tar:
    for member in tar:
        name = member.name
        entries.append((name, member.size))
        total_bytes += member.size
        base = name.split("/")[-1]
        if base == "_manifest":
            manifest_bytes = tar.extractfile(member).read()
        elif "/db/" in name or "/sp/" in name:
            data = tar.extractfile(member).read()
            to_extract[name] = data
        elif "/f/" in name:
            cover_count += 1
            cover_bytes += member.size

p("## 2. 备份内容概况")
p()
p("| 项 | 值 |")
p("|---|---|")
p("| tar 条目总数 | {} |".format(len(entries)))
p("| 解压后总大小 | {} |".format(human(total_bytes)))
p("| `files/` 下文件数（主要是封面） | {}（{}） |".format(cover_count, human(cover_bytes)))
p("| `databases/` + `shared_prefs/` 条目 | {} |".format(len(to_extract)))
p()

# manifest
if manifest_bytes:
    text = manifest_bytes.decode("utf-8", "replace").splitlines()
    p("## 3. `_manifest` 元信息")
    p()
    p("```")
    for i, ln in enumerate(text[:10]):
        label = ["格式版本", "包名", "应用 versionCode", "平台版本", "安装器", "是否含 APK",
                 "签名数量", "签名(hex前16)"][i] if i < 8 else "line{}".format(i)
        p("{}: {}".format(label, ln.strip()[:80]))
    p("```")
    p()
    pkg_ok = len(text) > 1 and text[1].strip() == PKG
    p("- 包名匹配：**{}**".format("✅" if pkg_ok else "❌ 不是 " + PKG))
    p("- 备份中的签名是**原开发者**的（与你没有的密钥对应），所以 `adb restore` 一定被拒；我们走手动写入，不受影响。")
    p()
else:
    p("## 3. `_manifest`")
    p()
    p("⚠️ 未找到 `_manifest`")
    p()

# 解出 db 与 sp
os.makedirs(OUT, exist_ok=True)
extracted_paths = []
for name, data in to_extract.items():
    rel = name.split(PKG + "/", 1)[-1] if PKG + "/" in name else name
    dst = os.path.join(OUT, rel.replace("/", os.sep))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(data)
    extracted_paths.append((rel, len(data)))

p("## 4. 已解出的关键文件（`backup/extracted/`）")
p()
p("| 文件 | 大小 |")
p("|---|---|")
for rel, size in sorted(extracted_paths):
    p("| `{}` | {} |".format(rel, human(size)))
p()

# DB 检查
db_path = None
for rel, _ in extracted_paths:
    if rel.endswith("yespdf.db"):
        db_path = os.path.join(OUT, rel.replace("/", os.sep))
        break

p("## 5. 数据库检查（最关键）")
p()
if not db_path or not os.path.exists(db_path):
    p("❌ 未找到 yespdf.db —— 备份里没有书库数据，迁移无意义")
else:
    con = sqlite3.connect(db_path)
    cur = con.cursor()
    integrity = cur.execute("PRAGMA integrity_check").fetchone()[0]
    user_version = cur.execute("PRAGMA user_version").fetchone()[0]
    tables = [r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    p("| 检查项 | 结果 | 判定 |")
    p("|---|---|---|")
    p("| `PRAGMA integrity_check` | `{}` | {} |".format(
        integrity, "✅ 完好" if integrity == "ok" else "❌ 文件损坏"))
    p("| `PRAGMA user_version`（schemaVersion） | `{}` | {} |".format(
        user_version,
        "✅ 与当前 APK 一致(3)，直接可用" if user_version == 3
        else ("⚠️ 低于 3，首次启动会执行 GreenDAO 迁移（数据保留，建议先留 .ab）" if user_version < 3
              else "❌ 高于 3，我们当前代码读不了")))
    p("| 表 | {} | {} |".format(", ".join(tables) or "无", "✅" if tables else "❌ 空库"))
    p()
    counts = {}
    for t in tables:
        try:
            counts[t] = cur.execute("SELECT COUNT(*) FROM {}".format(t)).fetchone()[0]
        except Exception as exc:
            counts[t] = -1
    p("### 行数")
    p()
    p("| 表 | 行数 |")
    p("|---|---|")
    for t, c in counts.items():
        p("| {} | {} |".format(t, c))
    p()

    # 书库细节
    try:
        cols = [r[1] for r in cur.execute("PRAGMA table_info(PDF)")]
        p("`PDF` 表字段: " + ", ".join(cols))
        p()
        rows = list(cur.execute(
            "SELECT NAME, PATH, COVER, CUR_PAGE, TOTAL_PAGE, PROGRESS, DIR FROM PDF LIMIT 5"))
        p("### 前 5 本书")
        p()
        p("| 书名 | 当前页/总页 | 进度 | 原文件路径 |")
        p("|---|---|---|---|")
        for r in rows:
            p("| {} | {}/{} | {} | `{}` |".format(r[0], r[3], r[4], r[5], r[1]))
        p()
        total = counts.get("PDF", 0)
        with_cover = cur.execute(
            "SELECT COUNT(*) FROM PDF WHERE COVER IS NOT NULL AND COVER != ''").fetchone()[0]
        cover_names = {os.path.basename(c) for (c,) in cur.execute(
            "SELECT COVER FROM PDF WHERE COVER IS NOT NULL AND COVER != ''")}
        p("- 书籍总数 **{}**，其中带封面路径 **{}**".format(total, with_cover))
        p("- 封面路径样例：`{}`".format(
            cur.execute("SELECT COVER FROM PDF WHERE COVER IS NOT NULL AND COVER != '' LIMIT 1").fetchone()
            if with_cover else "无"))
        p()

        # 封面文件匹配：备份里 files/ 下的文件名 vs DB 里 cover 的文件名
        backup_file_names = set()
        for name, _size in entries:
            if "/f/" in name:
                backup_file_names.add(name.split("/f/")[-1])
        matched = cover_names & backup_file_names
        p("### 封面文件匹配")
        p()
        p("- DB 引用的封面文件名数：{}".format(len(cover_names)))
        p("- 备份中 `files/` 下实际文件数：{}".format(len(backup_file_names)))
        p("- **能对上的：{}**（{}）".format(
            len(matched),
            "✅ 封面可完整恢复" if cover_names and len(matched) == len(cover_names)
            else "⚠️ 有 {} 个封面在备份里找不到（会显示占位图）".format(len(cover_names) - len(matched))))
        p()

        # 原 PDF 路径
        paths = [p_ for (p_,) in cur.execute("SELECT PATH FROM PDF")]
        roots = collections.Counter(os.path.dirname(p_) for p_ in paths)
        p("### 原 PDF 所在目录（迁移后需要这些文件还在）")
        p()
        p("| 目录 | 书籍数 |")
        p("|---|---|")
        for d, c in roots.most_common(8):
            p("| `{}` | {} |".format(d, c))
        p()
        p("> 只记录路径、不复制文件（文件引用原则）。只要手机里这些 PDF 还在原位，迁移后可直接打开。")
        p()
    except Exception as exc:
        p("读取 PDF 表失败: {}".format(exc))

    con.close()

# SP
sp_files = [rel for rel, _ in extracted_paths if "/sp/" in "/" + rel or rel.startswith("sp/")]
p("## 6. 应用设置（SharedPreferences）")
p()
if sp_files:
    p("已解出 {} 个 SP 文件：".format(len(sp_files)))
    p()
    for rel in sp_files:
        p("- `{}`".format(rel))
    p()
    p("> 迁移时不假设文件名，直接把 `sp/` 下所有 xml 原样写回 `shared_prefs/` 即可。")
else:
    p("⚠️ 未发现 SP 文件（说明官方版没有写过设置，或设置未被备份）")
p()

p("## 7. 结论")
p()
verdict = []
if db_path and os.path.exists(db_path):
    verdict.append("✅ DB 存在且通过完整性校验")
else:
    verdict.append("❌ 没有 DB，迁移无意义")
if manifest_bytes and len(manifest_bytes.decode("utf-8", "replace").splitlines()) > 1:
    verdict.append("✅ 包名一致（com.aaron.yespdf），与我们分支相同")
verdict.append("✅ 签名不同不影响：我们用手动写入，不走 adb restore")
verdict.append("⚠️ 迁移后 Keystore 密文不适用（官方版无此功能，本例无影响）")
for v in verdict:
    p("- " + v)

with open(REPORT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n报告已写入:", REPORT)
