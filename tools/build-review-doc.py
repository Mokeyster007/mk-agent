#!/usr/bin/env python
"""把 docs/playback-mvp-review.md 里的占位符替换成真实文件内容 / git diff。

占位符：
  @@FILE:<repo-relative-path>:<lang>@@  -> 带围栏的全文
  @@DIFF:<repo-relative-path>@@         -> git diff -U3（空 diff 会被标注）

所有内容按 LF 归一，避免 CRLF 文件在 Markdown 里显示成 ^M。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "playback-mvp-review.md"

FILE_RE = re.compile(r"^@@FILE:([^:]+):([A-Za-z0-9_+-]+)@@$", re.M)
DIFF_RE = re.compile(r"^@@DIFF:([^:]+)@@$", re.M)


def read_text(path: Path) -> str:
    return path.read_bytes().replace(b"\r\n", b"\n").decode("utf-8")


def fence(lang: str, body: str) -> str:
    body = body.rstrip("\n")
    return "```" + lang + "\n" + body + "\n```"


def git_diff(rel: str) -> str:
    out = subprocess.run(
        ["git", "diff", "-U3", "--", rel],
        cwd=str(ROOT), capture_output=True,
    )
    text = out.stdout.replace(b"\r\n", b"\n").decode("utf-8", "replace").strip()
    if not text:
        return "_(该文件当前没有 diff)_"
    return "```diff\n" + text + "\n```"


def main() -> int:
    doc = read_text(DOC)
    missing = []

    def sub_file(m: re.Match) -> str:
        rel, lang = m.group(1), m.group(2)
        p = ROOT / rel
        if not p.is_file():
            missing.append(rel)
            return "_(缺失：" + rel + ")_"
        return fence(lang, read_text(p))

    doc = FILE_RE.sub(sub_file, doc)
    doc = DIFF_RE.sub(lambda m: git_diff(m.group(1)), doc)

    DOC.write_text(doc, encoding="utf-8", newline="\n")

    lines = doc.count("\n") + 1
    print("wrote", DOC)
    print("lines:", lines)
    print("bytes:", len(doc.encode("utf-8")))
    if missing:
        print("MISSING:", ", ".join(missing))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
