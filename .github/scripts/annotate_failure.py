#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Publishes the interesting part of a failed step's log as GitHub error annotations, which are readable through the
check-runs API without downloading logs. Usage: annotate_failure.py <title> <log file>...
"""
import re
import sys
from pathlib import Path

PATTERN = re.compile(r"error|fatal|failed|failure|exception|undefined|unresolved|not found|cannot|FAILED|LNK\d+|C\d{4}:",
                     re.IGNORECASE)
WARNING = re.compile(r"warning", re.IGNORECASE)
CHUNK = 3500  # GitHub truncates longer annotation messages
MAX_ANNOTATIONS = 10  # GitHub keeps at most 10 error annotations per step


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main():
    title = sys.argv[1]
    budget = MAX_ANNOTATIONS
    for name in sys.argv[2:]:
        path = Path(name)
        if budget <= 0 or not path.is_file() or path.stat().st_size == 0:
            continue
        lines = path.read_text(errors="replace").splitlines()
        hits = ["%d: %s" % (i + 1, line.strip()[:300]) for i, line in enumerate(lines)
                if PATTERN.search(line) and not WARNING.search(line)]
        parts = []
        if hits:
            parts.append(("matched " + path.name, "\n".join(hits[-40:])[-CHUNK:]))
        tail = "\n".join(line for line in lines[-400:] if not WARNING.search(line))
        count = 4 if path.name.endswith(".log") else 1
        pieces = []
        end = len(tail)
        while end > 0 and len(pieces) < count:
            pieces.insert(0, tail[max(0, end - CHUNK):end])
            end -= CHUNK
        for index, text in enumerate(pieces):
            last = " (end)" if index == len(pieces) - 1 else ""
            parts.append(("tail-%d%s %s" % (index + 1, last, path.name), text))
        for label, text in parts:
            if text and budget > 0:
                print("::error title=%s %s::%s" % (title, label, escape(text)))
                budget -= 1


if __name__ == "__main__":
    main()
