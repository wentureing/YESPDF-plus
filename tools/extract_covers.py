#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 adb backup 的 .ab 中解出应用数据根目录（r/ 域）下的封面图片。

背景：上游把封面写成了
    PathUtils.getInternalAppDataPath() + "/" + 书名 + ".jpg"
而 getInternalAppDataPath() 返回 /data/user/0/<pkg>（dataDir 根），
所以这些图在 full backup 里位于 `apps/<pkg>/r/`，而不是 `f/`（files）。

用法：
    python tools/extract_covers.py <xxx.ab> [输出目录]
默认输出到 <repo>/backup/covers/
"""
import io
import os
import sys
import tarfile
import zlib

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "com.aaron.yespdf"


class Deflate(io.RawIOBase):
    def __init__(self, fp):
        self.fp = fp
        self.d = zlib.decompressobj(zlib.MAX_WBITS)
        self.buf = b""
        self.done = False

    def readable(self):
        return True

    def readinto(self, b):
        while not self.buf:
            if self.done:
                return 0
            chunk = self.fp.read(1 << 16)
            if chunk:
                self.buf = self.d.decompress(chunk)
            else:
                self.buf = self.d.flush()
                self.done = True
        n = min(len(b), len(self.buf))
        b[:n] = self.buf[:n]
        self.buf = self.buf[n:]
        return n


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    ab = os.path.abspath(argv[1])
    out_dir = os.path.abspath(argv[2]) if len(argv) > 2 else os.path.join(ROOT, "backup", "covers")
    os.makedirs(out_dir, exist_ok=True)

    fp = open(ab, "rb")
    magic, ver, comp, enc = [fp.readline().decode("ascii", "replace").strip() for _ in range(4)]
    if magic != "ANDROID BACKUP" or enc not in ("none", ""):
        print("不支持的备份格式:", magic, enc)
        return 1
    stream = io.BufferedReader(Deflate(fp), 1 << 16) if comp == "1" else io.BufferedReader(fp)

    total = 0
    saved = 0
    skipped = 0
    with tarfile.open(fileobj=stream, mode="r|") as tar:
        for m in tar:
            if not (m.name.startswith("apps/%s/r/" % PKG) and m.isfile()):
                continue
            total += 1
            rel = m.name.split("apps/%s/r/" % PKG, 1)[1]
            dst = os.path.join(out_dir, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            try:
                data = tar.extractfile(m).read()
                with open(dst, "wb") as f:
                    f.write(data)
                saved += 1
            except Exception:
                skipped += 1

    print("r/ 域文件数: {}, 已保存: {}, 跳过: {}".format(total, saved, skipped))
    print("输出目录:", out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
