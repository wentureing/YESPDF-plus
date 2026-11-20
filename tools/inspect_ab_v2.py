#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析 adb backup（.ab）的体积构成 —— 只读脚本 v2。

与 v1（inspect_ab.py，做兼容性检查）的分工：
- v1：解出 db/sp，检查 schemaVersion、字段、封面匹配（回答"能不能迁"）
- v2：**不解压到磁盘**，只流式扫一遍 tar，回答"这 155MB 里到底是什么"

输出：
- 控制台报告
- backup/ab_composition.md（便于贴给别人看）

用法（任选其一，见文末说明）：
    python tools/inspect_ab_v2.py
    python tools/inspect_ab_v2.py "backup/yespdf-official-v2.2.5.ab"
    python tools/inspect_ab_v2.py --top 25 "backup/xxx.ab"

要点：
- .ab = 4 行文本头 + **zlib deflate** 压缩的 tar（魔数 78 xx，不是 gzip 的 1f 8b）
- tar 必须流式读（r|），zlib 流不可 seek
- 大文件列表用堆维护，内存只留 Top N，不会把 155MB 读进内存
"""
import heapq
import io
import os
import sys
import tarfile
import zlib
import collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_AB = os.path.join(ROOT, "backup", "yespdf-official-v2.2.5.ab")
REPORT = os.path.join(ROOT, "backup", "ab_composition.md")
PKG = "com.aaron.yespdf"

# 体积分桶（用于看"封面是大图还是缩略图"）
SIZE_BUCKETS = [
    ("< 10 KB", 0, 10 * 1024),
    ("10–50 KB", 10 * 1024, 50 * 1024),
    ("50–200 KB", 50 * 1024, 200 * 1024),
    ("200–500 KB", 200 * 1024, 500 * 1024),
    ("> 500 KB", 500 * 1024, float("inf")),
]

lines = []


def p(text=""):
    print(text)
    lines.append(text)


def human(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "{:.1f} {}".format(n, unit)
        n /= 1024.0


class DeflateStream(io.RawIOBase):
    """zlib deflate 流式解压（.ab 的压缩体格式）。"""

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
        raise SystemExit("备份已加密（encrypt={}），无法分析".format(encryption))
    stream = io.BufferedReader(DeflateStream(fp), 1 << 16) if compressed == "1" \
        else io.BufferedReader(fp)
    return version, compressed, encryption, stream


def domain_of(name):
    """apps/<pkg>/f/a.jpg -> 'files(f)'；apps/<pkg>/db/x.db -> 'databases(db)'"""
    marker = PKG + "/"
    if marker not in name:
        if name.endswith("_manifest"):
            return "_manifest"
        if name.endswith("_meta"):
            return "_meta"
        return "其他"
    rel = name.split(marker, 1)[1]
    head = rel.split("/")[0]
    mapping = {
        "f": "files(f) —— 封面等派生文件",
        "db": "databases(db) —— 书库数据库",
        "sp": "shared_prefs(sp) —— 设置",
        "r": "root(r) —— 应用私有根",
        "c": "cache(c) —— 缓存",
        "ef": "external-files(ef)",
        "nb": "no-backup(nb)",
    }
    return mapping.get(head, "其他({})".format(head))


def main(argv):
    args = [a for a in argv[1:]]
    top_n = 15
    if "--top" in args:
        idx = args.index("--top")
        if idx + 1 < len(args):
            top_n = int(args[idx + 1])
            del args[idx:idx + 2]
    ab_path = os.path.abspath(args[0]) if args else DEFAULT_AB
    if not os.path.exists(ab_path):
        raise SystemExit("找不到文件: {}".format(ab_path))

    version, compressed, encryption, stream = open_ab(ab_path)
    ab_size = os.path.getsize(ab_path)

    dom_count = collections.Counter()
    dom_bytes = collections.Counter()
    dom_max = {}
    ext_count = collections.Counter()
    ext_bytes = collections.Counter()
    bucket_count = collections.Counter()
    files_subdirs = collections.Counter()
    biggest = []          # (size, name) 小顶堆，维持 Top N
    total = 0
    entries = 0
    db_entries = []
    sp_entries = []

    with tarfile.open(fileobj=stream, mode="r|") as tar:
        for m in tar:
            entries += 1
            size = m.size
            total += size
            dom = domain_of(m.name)
            dom_count[dom] += 1
            dom_bytes[dom] += size
            if size > dom_max.get(dom, -1):
                dom_max[dom] = size

            name = m.name
            if dom.startswith("files"):
                rel = name.split(PKG + "/f/", 1)[-1]
                if "/" in rel:
                    files_subdirs[rel.split("/")[0] + "/"] += 1
                ext = os.path.splitext(rel)[1].lower() or "(无扩展名)"
                ext_count[ext] += 1
                ext_bytes[ext] += size
                for label, lo, hi in SIZE_BUCKETS:
                    if lo <= size < hi:
                        bucket_count[label] += 1
                        break
                if len(biggest) < top_n:
                    heapq.heappush(biggest, (size, name))
                elif size > biggest[0][0]:
                    heapq.heapreplace(biggest, (size, name))
            elif dom.startswith("databases"):
                db_entries.append((name.split(PKG + "/", 1)[-1], size))
            elif dom.startswith("shared_prefs"):
                sp_entries.append((name.split(PKG + "/", 1)[-1], size))

    p("# adb backup 体积构成分析")
    p()
    p("| 项 | 值 |")
    p("|---|---|")
    p("| 文件 | `{}` |".format(os.path.relpath(ab_path, ROOT).replace("\\", "/")))
    p("| `.ab` 大小 | {} |".format(human(ab_size)))
    p("| 解压后总大小 | {} |".format(human(total)))
    p("| 压缩率 | {:.1f}%（越小说明内容越难压，多为已压缩的 JPEG） |".format(total / ab_size * 100 if ab_size else 0))
    p("| tar 条目数 | {} |".format(entries))
    p("| 备份格式版本 | {} |".format(version))
    p("| 压缩方式 | {} |".format("zlib deflate" if compressed == "1" else "无"))
    p()

    p("## 1. 按数据域（domain）拆分")
    p()
    p("| 域 | 文件数 | 大小 | 占比 | 平均 | 最大单文件 |")
    p("|---|---|---|---|---|---|")
    for dom, nbytes in dom_bytes.most_common():
        ratio = nbytes / total * 100 if total else 0
        p("| {} | {} | {} | {:.1f}% | {} | {} |".format(
            dom, dom_count[dom], human(nbytes), ratio,
            human(nbytes / dom_count[dom]) if dom_count[dom] else "-",
            human(dom_max.get(dom, 0))))
    p()

    if ext_count:
        p("## 2. `files/` 里的文件类型")
        p()
        p("| 扩展名 | 数量 | 总大小 | 占比 | 平均大小 |")
        p("|---|---|---|---|---|")
        for ext, n in ext_count.most_common():
            b = ext_bytes[ext]
            p("| `{}` | {} | {} | {:.1f}% | {} |".format(
                ext, n, human(b), b / total * 100 if total else 0,
                human(b / n) if n else "-"))
        p()
        if files_subdirs:
            p("子目录分布：" + "、".join("`{}`({})".format(k, v)
                                     for k, v in files_subdirs.most_common(10)))
            p()
        else:
            p("`files/` 下没有子目录（扁平存放）。")
            p()

        p("### 大小分布（判断是缩略图还是原图）")
        p()
        p("| 区间 | 文件数 |")
        p("|---|---|")
        for label, _lo, _hi in SIZE_BUCKETS:
            if bucket_count[label]:
                p("| {} | {} |".format(label, bucket_count[label]))
        p()

    if biggest:
        p("## 3. 最大的 {} 个文件".format(len(biggest)))
        p()
        p("| 大小 | 路径 |")
        p("|---|---|")
        for size, name in sorted(biggest, reverse=True):
            p("| {} | `{}` |".format(human(size), name.split(PKG + "/", 1)[-1]))
        p()

    if db_entries:
        p("## 4. `databases/`")
        p()
        p("| 文件 | 大小 |")
        p("|---|---|")
        for name, size in sorted(db_entries, key=lambda x: -x[1]):
            p("| `{}` | {} |".format(name, human(size)))
        p()

    if sp_entries:
        p("## 5. `shared_prefs/`")
        p()
        p("| 文件 | 大小 |")
        p("|---|---|")
        for name, size in sorted(sp_entries, key=lambda x: -x[1]):
            p("| `{}` | {} |".format(name, human(size)))
        p()

    p("## 6. 结论与迁移建议")
    p()
    files_dom = [d for d in dom_bytes if d.startswith("files")]
    files_bytes = sum(dom_bytes[d] for d in files_dom) if files_dom else 0
    core_bytes = total - files_bytes
    p("- 核心数据（db + sp）约 **{}**，占 **{:.1f}%**".format(
        human(core_bytes), core_bytes / total * 100 if total else 0))
    p("- 封面/派生文件约 **{}**，占 **{:.1f}%**".format(
        human(files_bytes), files_bytes / total * 100 if total else 0))
    avg = files_bytes / sum(ext_count.values()) if ext_count else 0
    if avg:
        p("- 单个图片平均 **{}**{}".format(
            human(avg), "，符合缩略图量级（≤512px JPEG 通常几十 KB）" if avg < 200 * 1024
            else "，偏大，可能是原图或早期全屏 PNG 封面"))
    p()
    p("**迁移取舍**：")
    p()
    p("1. **只迁核心数据**：`db/yespdf.db` + `sp/*.xml`，约 {}，几秒完成；封面会显示占位图".format(
        human(core_bytes)))
    p("2. **连封面一起迁**：{}，首次写入耗时更长，但书库看起来完整".format(human(total)))
    p("3. 若只迁 DB，可在应用里重新生成封面（打开书后自动截取首页），但会覆盖原有封面")
    p()
    p("> 想确认「能不能迁」（schemaVersion / 字段 / 封面匹配率），请运行 `tools/inspect_ab.py`。")

    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n报告已写入:", REPORT)


if __name__ == "__main__":
    main(sys.argv)
