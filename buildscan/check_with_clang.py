#!/usr/bin/env python3
"""Parse every file in OUTDIR/*/compile_commands.json with clang -fsyntax-only
and summarise the errors, as a stand-in for Eclipse CDT's indexer."""
import collections
import concurrent.futures
import glob
import json
import re
import subprocess
import sys

outdir = sys.argv[1]
entries = []
for f in sorted(glob.glob(f"{outdir}/*/compile_commands.json")):
    comp = f.split("/")[-2]
    entries += [(comp, e) for e in json.load(open(f))]


def check(item):
    comp, e = item
    args = [a for a in e["arguments"] if a != "-c"]
    args[1:1] = ["-fsyntax-only", "-ferror-limit=0", "-w"]
    p = subprocess.run(args, cwd=e["directory"], capture_output=True, text=True)
    errs = [l for l in p.stderr.splitlines() if ": error:" in l or ": fatal error:" in l]
    return comp, e["file"], errs


per_kind = collections.Counter()
examples = {}
bad_files = 0
by_comp = collections.Counter()
with concurrent.futures.ThreadPoolExecutor(16) as ex:
    for comp, path, errs in ex.map(check, entries):
        if errs:
            bad_files += 1
            by_comp[comp] += 1
        for e in errs:
            msg = re.sub(r"^.*?error: ", "", e)
            kind = re.sub(r"'[^']*'", "'X'", msg)
            per_kind[kind] += 1
            examples.setdefault(kind, e)
print(f"{len(entries)} files, {bad_files} with errors")
print("\nErrors by kind:")
for k, n in per_kind.most_common(40):
    print(f"{n:6}  {k}\n        e.g. {examples[k][:220]}")
print("\nFiles with errors by component:")
for c, n in by_comp.most_common(40):
    print(f"{n:4}  {c}")
