#!/usr/bin/env python3
"""Folds one community verdict (a GitHub issue body written by the Droidputter app) into apps/verdicts.json.

The app submits a verdict as a prefilled issue whose body carries a ```json block with the Verdict record
(android/core .../catalog/Verdict.kt: name, env, firmware_sha256, shim_commit, board, result, note, date,
reporter). .github/workflows/verdicts.yml runs this on every `verdict` issue; the body's reporter (the phone's anonymous device id) is the reporter of record, else the issue author; the
issue's creation date is the date, whatever the body says.

Usage:
    python3 tools/fold_verdict.py --body-file body.md --reporter LOGIN --date 2026-09-03 [--verdicts apps/verdicts.json]
                                  [--dry-run] [--github-output]
Exit 0 = folded or duplicate (status printed), exit 2 = invalid (reason printed). Pure stdlib.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VERDICTS = REPO_ROOT / "apps" / "verdicts.json"
FIELD_ORDER = ["name", "env", "firmware_sha256", "shim_commit", "board", "result", "note", "date", "reporter"]
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
BLOCK_RE = re.compile(r"```json\s*\r?\n(.*?)\r?\n\s*```", re.S | re.I)


def extract_block(body: str) -> str | None:
    m = BLOCK_RE.search(body or "")
    return m.group(1) if m else None


def validate(raw: dict, reporter: str | None, date: str | None) -> tuple[dict | None, str | None]:
    if not isinstance(raw, dict):
        return None, "JSON block is not an object"
    def text(key, required=False, limit=200):
        v = raw.get(key)
        if v is None:
            if required:
                raise ValueError(f"missing {key}")
            return ""
        if not isinstance(v, str):
            raise ValueError(f"{key} is not a string")
        v = v.strip()
        if required and not v:
            raise ValueError(f"empty {key}")
        return v[:limit]
    try:
        rec = {
            "name": text("name", True, 80),
            "env": text("env", True, 40),
            "firmware_sha256": text("firmware_sha256", True, 64).lower(),
            "shim_commit": text("shim_commit", False, 40) or None,
            "board": text("board", True, 40),
            "result": text("result", True, 10).lower(),
            "note": text("note", False, 500),
            "date": (date or text("date", False, 10) or "").strip(),
            # The body's reporter wins when present: since 2026-09-04 the build proxy files every issue under the
            # repo owner's identity while the phone puts its anonymous "device-xxxxxxxx" id in the body; the
            # issue author is only the fallback for hand-written issues.
            "reporter": (text("reporter", False, 60) or reporter or None),
        }
    except ValueError as e:
        return None, str(e)
    if not SHA256_RE.match(rec["firmware_sha256"]):
        return None, "firmware_sha256 is not 64 hex characters"
    if rec["result"] not in ("works", "broken"):
        return None, "result must be 'works' or 'broken'"
    if not DATE_RE.match(rec["date"]):
        return None, "date is not YYYY-MM-DD"
    return rec, None


def same(a: dict, b: dict) -> bool:
    return all(a.get(k) == b.get(k) for k in ("name", "env", "firmware_sha256", "result", "reporter"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--body-file", help="issue body (default: stdin)")
    ap.add_argument("--reporter", help="issue author login (authoritative)")
    ap.add_argument("--date", help="issue creation date YYYY-MM-DD (authoritative)")
    ap.add_argument("--verdicts", default=str(DEFAULT_VERDICTS))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--github-output", action="store_true", help="also write status=/reason= to $GITHUB_OUTPUT")
    a = ap.parse_args()

    body = Path(a.body_file).read_text(encoding="utf-8", errors="replace") if a.body_file else sys.stdin.read()

    def finish(status: str, reason: str = "", record: dict | None = None) -> int:
        print(json.dumps({"status": status, "reason": reason, "record": record}, ensure_ascii=False))
        if a.github_output and os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
                f.write(f"status={status}\n")
                f.write("reason=" + reason.replace("\n", " ")[:500] + "\n")
        return 0 if status in ("folded", "duplicate") else 2

    block = extract_block(body)
    if block is None:
        return finish("invalid", "no ```json block in the issue body")
    try:
        raw = json.loads(block)
    except json.JSONDecodeError as e:
        return finish("invalid", f"JSON block does not parse: {e.msg} at line {e.lineno}")
    rec, err = validate(raw, a.reporter, a.date)
    if err:
        return finish("invalid", err)

    path = Path(a.verdicts)
    existing = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else []
    if not isinstance(existing, list):
        return finish("invalid", f"{path} is not a JSON array")
    if any(same(rec, e) for e in existing):
        return finish("duplicate", "an identical verdict (name, env, sha256, result, reporter) is already recorded", rec)
    ordered = {k: rec[k] for k in FIELD_ORDER if rec.get(k) is not None}
    if not a.dry_run:
        existing.append(ordered)
        path.write_text(json.dumps(existing, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return finish("folded", "", ordered)


if __name__ == "__main__":
    sys.exit(main())
