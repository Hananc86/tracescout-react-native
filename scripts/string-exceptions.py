#!/usr/bin/env python3
"""Rewrite an enumerated set of diagnostic message strings.

These are the free-text `detail` halves of typed refusals: the typed reason
beside them is the contract and is never touched. Each entry is an exact match
asserted to occur once, so this is a short audited list rather than a blanket
rewrite of string literals.
"""
import sys

EXCEPTIONS = [
    ("android/src/main/java/com/tracescout/rn/MetadataOnlyCarrier.kt",
     '"the ledger holds a durable terminal claim; terminal emission is blocked on Q-3"',
     '"the ledger holds a durable terminal claim; terminal emission is out of scope"'),
    ("android/src/main/java/com/tracescout/rn/MetadataOnlyCarrier.kt",
     '"an absent first sequence is the FINALITY-ONLY shape (C18, blocked on Q-3)"',
     '"an absent first sequence is the finality-only shape, which is out of scope"'),
    ("android/src/main/java/com/tracescout/rn/MetadataOnlyCarrier.kt",
     '"the frozen snapshot asserts terminal; terminal emission is blocked on Q-3"',
     '"the frozen snapshot asserts terminal; terminal emission is out of scope"'),
]


def main():
    n = 0
    for path, old, new in EXCEPTIONS:
        s = open(path, encoding="utf-8").read()
        c = s.count(old)
        if c == 0 and s.count(new):
            continue  # already applied
        if c != 1:
            raise SystemExit(f"REFUSED: {path}: exact match x{c} for {old[:50]}")
        open(path, "w", encoding="utf-8").write(s.replace(old, new))
        n += 1
    print(f"diagnostic strings rewritten: {n} of {len(EXCEPTIONS)}")


if __name__ == "__main__":
    main()
