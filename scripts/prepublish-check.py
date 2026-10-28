#!/usr/bin/env python3
"""Prepublish check over the files this package actually ships.

It reads the packed file list from `npm pack --dry-run` rather than walking the
working tree, so it inspects the artifact a customer receives. Anything it
reports is a release blocker; exit status is 0 only when every class is clean.
"""
import json
import os
import re
import subprocess
import sys

CLASSES = [
    ('decorative emoji',
     r'[\U0001F300-\U0001FAFF☀-➿⬀-⯿️⚠✅❌]'),
    ('internal task id', r'\bT-\d{2,4}\b'),
    ('internal decision id', r'\bD-\d+\b|\bQ-\d+\b|\bMC-[A-Z0-9-]{4,}\b'),
    ('internal board reference',
     r'\bboard (?:event|decision|ruling)|\blead rulings?\b'),
    ('internal spec section', r'\bTEST_MATRIX\b|(?:PLAN|REV\d+)\s*§|§\s*[\dA-Z]'),
    ('maintainer name', r'\bHanan\b|\bHANAN\b'),
    ('private fixture app', r'\bNorthstar\b'),
    ('competitor name',
     r'\b(?:LogRocket|Datadog|Sentry|FullStory|Smartlook|Hotjar|Mixpanel'
     r'|Amplitude|Bugsnag|Crashlytics|Embrace|Instabug)\b'),
    ('private host or address',
     r'cohenscloud\.com|\b10\.100\.\d+\.\d+\b|\b10\.200\.\d+\.\d+\b'
     r'|\b192\.168\.\d+\.\d+\b|pve-t560'),
    ('private filesystem path', r'/root/|/srv/repos/|/home/ci/|/Users/\w+/'),
    ('ingest credential', r'\btsk_[A-Za-z0-9_-]{8,}'),
    ('bearer or api key literal',
     r'(?i)(?:authorization|api[_-]?key|secret|password|token)\s*[:=]\s*'
     r'["\'][A-Za-z0-9_\-./+]{16,}["\']'),
    ('private evidence path', r'rn-uat-evidence|t\d{3}-work'),
]
# A literal a rule would otherwise flag, with the reason it is legitimate.
ALLOW = [
    # The MIT text names no one; the copyright line is required and intended.
    (r'Copyright \(c\) 20\d\d TraceScout', 'required copyright notice'),
    # A functional constant, not a credential: it is the sentinel the position
    # safety check compares against. Renaming it would change behaviour.
    (r'(?:TOKEN|Token)\s*=\s*"ts-position-safe"', 'functional sentinel constant'),
]


def packed_files(pkg_dir):
    out = subprocess.run(['npm', 'pack', '--dry-run', '--json'],
                         cwd=pkg_dir, capture_output=True, text=True)
    if out.returncode != 0:
        print(out.stderr[-2000:], file=sys.stderr)
        raise SystemExit('COULD_NOT_RUN: npm pack --dry-run failed')
    data = json.loads(out.stdout)
    return [f['path'] for f in data[0]['files']]


def main():
    pkg = sys.argv[1] if len(sys.argv) > 1 else '.'
    files = packed_files(pkg)
    print(f'packed files: {len(files)}')
    allow = [(re.compile(p), why) for p, why in ALLOW]
    findings = []
    for name, pat in CLASSES:
        rx = re.compile(pat)
        hits = []
        for rel in files:
            p = os.path.join(pkg, rel)
            try:
                s = open(p, encoding='utf-8', errors='replace').read()
            except OSError:
                continue
            for m in rx.finditer(s):
                if any(a.search(s[max(0, m.start() - 60):m.end() + 60]) for a, _ in allow):
                    continue
                line = s.count('\n', 0, m.start()) + 1
                hits.append((rel, line, m.group(0)[:60]))
        status = 'clean' if not hits else f'{len(hits)} HITS'
        print(f'  {name:28s} {status}')
        for rel, line, txt in hits[:8]:
            print(f'      {rel}:{line}: {txt!r}')
        if len(hits) > 8:
            print(f'      ... and {len(hits) - 8} more')
        if hits:
            findings.append((name, len(hits)))
    print()
    if findings:
        print('BLOCKED: ' + ', '.join(f'{n} ({c})' for n, c in findings))
        return 1
    print('OK: every class clean across the packed file list')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
