#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
架构静态检查（CI 用，零第三方依赖）。

三类检查：
1. 依赖方向：包之间只能由高层指向低层，且禁止几条固定环
2. 数量阈值：单文件行数、单目录（直接子文件）数量
3. 注释安全：块注释/KDoc 被 `*/` 提前闭合
   （曾导致 Kotlin 报上百行 "Expecting member declaration"）

用法：python3 .github/scripts/arch_check.py
     失败时退出码 1 并打印违规清单。
"""
import os
import re
import sys
import collections

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
APP_JAVA = os.path.join(ROOT, "app", "src", "main", "java")
LIB_JAVA = os.path.join(ROOT, "android-pdf-viewer", "src", "main", "java")
PKG_ROOT = "com.aaron.yespdf"

# 分层：数字越小越底层（依赖只能由高层指向低层）
LAYERS = {
    "common": 0,      # 设置、数据库、工具、安全
    "data": 1,        # 应用数据管理
    "filepicker": 1,  # 文件选择 / 扫描
    "settings": 1,
    "about": 1,
    "main": 2,        # 界面聚合
    "preview": 2,
}

# 同层内的额外禁止，避免双向依赖
FORBIDDEN_EDGES = {
    ("data", "filepicker"),
    ("filepicker", "data"),
    ("preview", "main"),
}

# 已知例外：登记后只报告不阻断（历史遗留耦合）
WAIVERS = {
    ("common", "main"): "ShortcutUtils.kt 需启动 MainActivity 创建快捷方式（上游既有设计）",
}

MAX_FILE_LINES = 1800
MAX_DIR_FILES = 25

IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)", re.M)
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.M)


def collect_sources():
    result = []
    for base in (APP_JAVA, LIB_JAVA):
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                if name.endswith((".kt", ".java")):
                    result.append(os.path.join(dirpath, name))
    return sorted(result)


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def layer_of(pkg):
    if not pkg.startswith(PKG_ROOT + "."):
        return None
    seg = pkg[len(PKG_ROOT) + 1:].split(".")[0]
    return seg if seg in LAYERS else None


def check_kdoc(text):
    """返回块注释被提前闭合的可疑行：[(行号, 片段), ...]"""
    problems = []
    in_block = False
    for lineno, line in enumerate(text.splitlines(), 1):
        if not in_block:
            open_idx = line.find("/**")
            if open_idx < 0:
                continue
            in_block = True
            rest = line[open_idx + 3:]
        else:
            rest = line

        if "*/" in rest:
            after = rest.split("*/", 1)[1]
            in_block = False
            if after.strip():
                problems.append((lineno, line.strip()[:100]))
    return problems


def main():
    files = collect_sources()
    if not files:
        print("no source files found:", APP_JAVA)
        return 1

    edges = collections.Counter()
    dir_files = collections.Counter()
    dir_lines = collections.Counter()
    layer_files = collections.Counter()
    layer_lines = collections.Counter()
    violations = []
    kdoc_problems = []

    for path in files:
        text = read(path)
        line_count = text.count("\n") + 1

        pkg_match = PACKAGE_RE.search(text)
        pkg = pkg_match.group(1) if pkg_match else ""
        layer = layer_of(pkg)

        directory = os.path.dirname(rel(path))
        dir_files[directory] += 1
        dir_lines[directory] += line_count

        if line_count > MAX_FILE_LINES:
            violations.append("文件过大: {} 共 {} 行（上限 {}）".format(
                rel(path), line_count, MAX_FILE_LINES))

        if layer:
            layer_files[layer] += 1
            layer_lines[layer] += line_count

        for match in IMPORT_RE.finditer(text):
            target = layer_of(match.group(1))
            if target is None or not layer or target == layer:
                continue
            edges[(layer, target)] += 1
            if (layer, target) in WAIVERS:
                continue
            if (layer, target) in FORBIDDEN_EDGES:
                violations.append("禁止依赖: {} -> {}（{}）".format(
                    layer, target, rel(path)))
            elif LAYERS[layer] < LAYERS[target]:
                violations.append("方向错误: 高层 {} 依赖低层 {}（{}）".format(
                    layer, target, rel(path)))

        for lineno, snippet in check_kdoc(text):
            kdoc_problems.append("{}:{} {}".format(rel(path), lineno, snippet))

    for directory, count in dir_files.items():
        if count > MAX_DIR_FILES:
            violations.append("目录文件过多: {} 有 {} 个（上限 {}）".format(
                directory.split("/java/")[-1], count, MAX_DIR_FILES))

    print("=" * 78)
    print("架构检查报告")
    print("=" * 78)
    print("源文件总数: {}".format(len(files)))

    print("\n[分层规模]")
    for layer in sorted(layer_files, key=lambda x: LAYERS.get(x, 99)):
        print("  L{:<2} {:<12} {:>3} 文件  {:>6} 行".format(
            LAYERS.get(layer, "?"), layer, layer_files[layer], layer_lines[layer]))

    print("\n[目录规模 Top5]")
    for directory in sorted(dir_files, key=lambda d: -dir_files[d])[:5]:
        print("  {:<52} {:>3} 文件  {:>6} 行".format(
            directory.split("/java/")[-1][-52:], dir_files[directory], dir_lines[directory]))

    print("\n[依赖方向]（源 -> 目标 : 引用次数）")
    if not edges:
        print("  （无跨包依赖）")
    for (src, dst), count in sorted(edges.items(), key=lambda kv: (-kv[1], kv[0])):
        print("  {:<12} -> {:<12} : {}".format(src, dst, count))

    if WAIVERS:
        print("\n[已知例外（不阻断）]")
        for (src, dst), reason in WAIVERS.items():
            print("  {:<10} -> {:<10} : {}".format(src, dst, reason))

    print("\n[注释安全]")
    if kdoc_problems:
        for item in kdoc_problems:
            print("  !! " + item)
    else:
        print("  OK：没有块注释被提前闭合")

    print("\n[违规]")
    if violations:
        for item in violations:
            print("  - " + item)
    else:
        print("  无")

    failed = bool(violations) or bool(kdoc_problems)
    print("\n结果: {}".format("FAILED" if failed else "PASSED"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
