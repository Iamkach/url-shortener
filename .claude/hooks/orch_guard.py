#!/usr/bin/env python3
"""PreToolUse governance hook — always on for this repo.

Product code (``url-shortener-service/src/**``, ``orchestrator/src/**``, ``specs/**``) may only be
changed from inside an orchestrator run: a Claude Code session with ``ORCH_RUN_ID`` set, whose
current node's stage permits the path (``ORCH_ALLOW_PATHS``, exported by the ``agent`` executor).
Docs and meta (``CLAUDE.md``, ``README.md``, ``.gitignore``, ``docs/**/*.md``, root ``*.md``) are
always editable so a human can work normally.

stdin: the PreToolUse hook JSON ({"tool_input": {"file_path": ...}, ...}).
stdout: {"hookSpecificOutput": {"hookEventName": "PreToolUse",
         "permissionDecision": "allow"|"deny", "permissionDecisionReason": "..."}}
"""
import json
import os
import re
import sys

GOVERNED = ["url-shortener-service/src/**", "orchestrator/src/**", "specs/**"]
ALWAYS_EXEMPT = ["CLAUDE.md", "README.md", ".gitignore", "docs/**/*.md", "*.md"]
WALKTHROUGH = "docs/executor-seam-walkthrough.md"

# This script lives at <repo-root>/.claude/hooks/orch_guard.py — anchor on the repo
# root derived from its own location, never on os.getcwd() (a session started in a
# subdirectory would otherwise compute paths that match no glob and fail open).
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def glob_to_regex(glob):
    i, n, out = 0, len(glob), ["^"]
    while i < n:
        c = glob[i]
        if c == "*":
            if i + 1 < n and glob[i + 1] == "*":
                out.append(".*")
                i += 2
                if i < n and glob[i] == "/":
                    i += 1
                continue
            out.append("[^/]*")
        elif c == "?":
            out.append("[^/]")
        elif c in ".^$+{}()[]|\\":
            out.append("\\" + c)
        else:
            out.append(c)
        i += 1
    out.append("$")
    return "".join(out)


def matches(path, glob):
    return re.match(glob_to_regex(glob), path) is not None


def repo_relative(file_path):
    try:
        rel = os.path.relpath(os.path.abspath(file_path), REPO_ROOT)
    except ValueError:
        # Different Windows drive — cannot be inside the repo root.
        return None
    rel = rel.replace(os.sep, "/")
    if rel == ".." or rel.startswith("../"):
        # Path escapes the repo root.
        return None
    return rel


def emit(decision, reason):
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": decision,
        "permissionDecisionReason": reason,
    }}))
    sys.exit(0)


def main():
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        emit("allow", "orch_guard: no parseable hook input")

    file_path = (data.get("tool_input") or {}).get("file_path") or ""
    if not file_path:
        emit("allow", "orch_guard: tool call has no file_path")

    rel = repo_relative(file_path)

    if rel is None:
        emit("deny", "orch_guard: cannot resolve %s against the repo root — refusing edit" % file_path)

    if any(matches(rel, g) for g in ALWAYS_EXEMPT):
        emit("allow", "orch_guard: %s is docs/meta — always editable" % rel)

    governed = any(matches(rel, g) for g in GOVERNED)
    run_id = os.environ.get("ORCH_RUN_ID", "").strip()

    if not run_id:
        if governed:
            emit("deny",
                 "orch_guard: %s is governed product code — change it through an orchestrator run "
                 "(see %s) or edit docs/meta directly. No ORCH_RUN_ID in this session."
                 % (rel, WALKTHROUGH))
        emit("allow", "orch_guard: %s is outside the governed trees" % rel)

    allow_paths = [p.strip() for p in os.environ.get("ORCH_ALLOW_PATHS", "").split(",") if p.strip()]
    stage = os.environ.get("ORCH_NODE_STAGE", "?")
    if any(matches(rel, g) for g in allow_paths):
        emit("allow", "orch_guard: %s permitted for stage %s" % (rel, stage))
    emit("deny",
         "orch_guard: run %s node stage %s may only write [%s] — %s is outside that set"
         % (run_id, stage, ", ".join(allow_paths) or "(none)", rel))


if __name__ == "__main__":
    main()
