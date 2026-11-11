#!/usr/bin/env python3
"""零注释守门：扫描仓库源码里是否还有注释。

用法：
    python tools/check_no_comments.py -Staged    只查暂存区将要提交的新增行
    python tools/check_no_comments.py -All       全仓库对账
    python tools/check_no_comments.py -Path a.kt 只查某个文件

退出码：0 = 干净；1 = 发现问题。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

CODE_EXTS = {
    ".kt", ".kts", ".java", ".xml", ".gradle", ".ps1", ".pro", ".properties", ".smali", ".py",
}

HASH_EXTS = {".gradle", ".ps1", ".pro", ".properties", ".py"}
SLASH_EXTS = {".kt", ".kts", ".java", ".smali"}
XML_EXTS = {".xml"}

SKIP_DIRS = {
    ".git", "build", "build-alt", ".gradle", ".kotlin", "work", "out", "node_modules", "__pycache__",
}

STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"' + "|" + r"'(?:[^'\\]|\\.)*'")


def strip_literals(line: str) -> str:
    return STRING_LITERAL.sub('""', line)


def classify(path: str, line: str) -> str | None:
    ext = "." + path.rsplit(".", 1)[-1].lower() if "." in path else ""
    text = strip_literals(line)
    stripped = text.strip()
    if ext in SLASH_EXTS:
        if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
            return "line/block comment"
        if "/*" in text and "*/" in text and text.find("/*") < text.find("*/"):
            return "inline block comment"
    elif ext in HASH_EXTS:
        if stripped.startswith("#!") or stripped.startswith("# -*-"):
            return None
        if stripped.startswith("#"):
            return "hash comment"
    elif ext in XML_EXTS:
        if stripped.startswith("<!--") or stripped.endswith("-->"):
            return "xml comment"
    return None


def iter_files(root: str):
    import os
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            path = os.path.join(base, name)
            ext = "." + name.rsplit(".", 1)[-1].lower() if "." in name else ""
            if ext in CODE_EXTS:
                yield path


def scan_file(root: str, path: str) -> list[str]:
    problems = []
    rel = os.path.relpath(path, root) if path.startswith(root) else path
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for number, line in enumerate(handle, 1):
                kind = classify(path, line)
                if kind:
                    problems.append(f"{rel}:{number}: {kind}: {line.rstrip()}")
    except OSError as error:
        problems.append(f"{rel}: 读不了：{error}")
    return problems


def scan_staged(root: str) -> list[str]:
    problems = []
    try:
        names = subprocess.check_output(
            ["git", "diff", "--cached", "--name-only", "--diff-filter=ACM"],
            cwd=root, text=True, encoding="utf-8", errors="replace",
        ).split()
    except subprocess.CalledProcessError as error:
        print(f"git diff 失败：{error}", file=sys.stderr)
        return problems
    for name in names:
        ext = "." + name.rsplit(".", 1)[-1].lower() if "." in name else ""
        if ext not in CODE_EXTS:
            continue
        try:
            diff = subprocess.check_output(
                ["git", "diff", "--cached", "-U0", "--", name], cwd=root, text=True, encoding="utf-8", errors="replace",
            )
        except subprocess.CalledProcessError:
            continue
        new_line = 0
        for raw in diff.splitlines():
            if raw.startswith("@@") and "-" in raw:
                match = re.search(r"\+(\d+)", raw)
                new_line = int(match.group(1)) - 1 if match else new_line
                continue
            if raw.startswith("+++") or raw.startswith("---"):
                continue
            if raw.startswith("+"):
                new_line += 1
                kind = classify(name, raw[1:])
                if kind:
                    problems.append(f"{name}:{new_line}: {kind}: {raw[1:].rstrip()}")
            elif not raw.startswith("-"):
                new_line += 1
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-All", action="store_true", dest="all_files")
    parser.add_argument("-Staged", action="store_true", dest="staged")
    parser.add_argument("-Path", dest="path")
    parser.add_argument("-Root", dest="root", default=".")
    args = parser.parse_args()

    import os
    root = os.path.abspath(args.root)
    problems: list[str] = []

    if args.staged:
        problems = scan_staged(root)
    elif args.path:
        problems = scan_file(root, os.path.abspath(args.path))
    else:
        for path in iter_files(root):
            problems.extend(scan_file(root, path))

    if problems:
        for item in problems[:200]:
            print(item)
        if len(problems) > 200:
            print(f"... 还有 {len(problems) - 200} 条")
        print(f"FAIL: {len(problems)} 处注释")
        return 1
    print("OK: no comments")
    return 0


if __name__ == "__main__":
    sys.exit(main())


