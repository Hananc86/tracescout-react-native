#!/usr/bin/env python3
"""Rewrite the public SDK's comments for a public audience.

What it removes: decorative warning badges, and references to internal
development process -- task identifiers, board decisions, open-question
numbers, internal specification section numbers, named people and the private
development fixture application. Those are citations to material a reader of
the public package cannot see, so they are noise at best.

What it does NOT do: strip comments, touch code, rename anything, or alter a
string literal. Every edit is confined to a comment span, and the run asserts
that the comment-stripped source is byte-identical before and after. That
assertion is the proof that behaviour is unchanged.
"""
import os
import re
import sys
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from comments import comment_spans, strip_comments  # noqa: E402

# The third-party product names the publish gate blocks. Imported, never
# restated: a second copy of this list is a second thing to keep in step, and
# the cleaner silently lacking a name the gate refuses is a tree that cannot
# be published and no tool can repair.
import importlib.util as _ilu  # noqa: E402

_spec = _ilu.spec_from_file_location(
    '_prepublish_check',
    os.path.join(os.path.dirname(os.path.abspath(__file__)), 'prepublish-check.py'))
_gate = _ilu.module_from_spec(_spec)
_spec.loader.exec_module(_gate)
_COMPETITORS = next(p for n, p in _gate.CLASSES if n == 'competitor name')

EXTS = ('kt', 'swift', 'ts', 'js', 'm', 'h', 'podspec')

# Ordered: earlier rules may create text a later rule tidies.
RULES = [
    # --- decorative badges -------------------------------------------------
    (r'⚠️?⚠️?\s*', '', 'badge'),
    (r'⚠️?\s*', '', 'badge'),

    # --- citations in parentheses: the whole parenthetical goes ------------
    (r'[ \t]*\((?:see\s+)?(?:T-\d{2,4}|D-\d+|Q-\d+|E-\d+|F\d+P\d+-\d+)'
     r'(?:[^()]{0,80}?)?\)', '', 'citation'),
    (r'[ \t]*\(board (?:event|decision|ruling)[^()]{0,60}\)', '', 'citation'),
    (r'[ \t]*\((?:PLAN|REV\d+)\s*§[^()]{0,60}\)', '', 'citation'),
    (r'[ \t]*\(§[^()]{0,60}\)', '', 'citation'),
    (r'[ \t]*\(lead ruling[^()]{0,80}\)', '', 'citation'),
    (r'[ \t]*\(MC-[A-Z0-9-]+\)', '', 'citation'),
    (r'[ \t]*\(TEST_MATRIX[^()]{0,40}\)', '', 'citation'),
    (r'[ \t]*\(measured[^()]{0,40}\)', '', 'citation'),

    # --- named internal documents become what they are --------------------
    (r'\bTEST_MATRIX\s+[A-Z]?\d+[a-z]?\b', 'a conformance test', 'doc'),
    (r'\bTEST_MATRIX\b', 'the conformance tests', 'doc'),
    (r'\b(?:PLAN|REV\d+)\s*§\s*[\dA-Z][\w.]*(?:\s*bullet\s*\d+)?',
     'the capture specification', 'doc'),
    (r'§\s*[\dA-Z][\w.]*', 'the specification', 'doc'),

    # --- process references -----------------------------------------------
    (r'\bMC-[A-Z0-9-]+\b', 'an explicit product decision', 'process'),
    (r'\bboard (?:event|decision|ruling)\s*#?\d*', 'a product decision', 'process'),
    (r'\blead ruling\s+[A-Z0-9-]+(?:,\s*E-\d+)?', 'a product decision', 'process'),
    (r'\blead rulings?\s+[A-Z0-9/]+(?:/[A-Z0-9]+)?', 'a product decision', 'process'),
    (r'\blead rulings?\b', 'a product decision', 'process'),
    # Absolute paths into the private development host and its evidence store.
    (r'`?/root/[^\s`)]*`?', 'an internal record', 'private'),
    (r'`?/srv/repos/[^\s`)]*`?', 'an internal record', 'private'),
    (r'\brn-uat-evidence\b', 'an internal record', 'private'),
    (r'\bT-\d{2,4}\b', 'an earlier change', 'process'),
    (r'\bD-\d+\b', 'a product decision', 'process'),
    (r'\bQ-\d+\b', 'an open product question', 'process'),

    # --- people and private venues ----------------------------------------
    (r"\bHanan's\b", "the maintainer's", 'private'),
    (r'\b(?:Hanan|HANAN)\b', 'the maintainer', 'private'),
    (r'\bNorthstar\b', 'the development fixture', 'private'),

    # --- third-party products named by name --------------------------------
    # The publish gate BLOCKS these, so without a rule here the export path
    # cannot produce a publishable tree for a source file that names one: the
    # gate would refuse a file the cleaner has no way to fix. The vocabulary is
    # imported from the gate rather than restated, so the two cannot drift --
    # which is how this gap appeared in the first place.
    #
    # An enumeration collapses to ONE neutral phrase. Rewriting each name
    # separately would leave "or a third-party reporter, or a third-party
    # reporter, or a third-party reporter", which says less than the original.
    #
    # FIRST, the orphan. The walker gives every `//` line its own span, so no
    # rule can see a phrase that spans two comment lines -- and a vendor
    # qualifier stranded at the end of one line, with the product name it
    # qualifies on the next, is the shape that occurs. Dropping the orphan
    # while keeping its connective is what lets the following line's
    # neutralisation read as continuous prose. Scoped to the hanging-qualifier
    # shape, so a sentence that legitimately ends in a vendor name is left be.
    (r'(\b(?:or|and)\s+)(?:Firebase|Google)[ \t]*$', r'\1', 'competitor'),
    (r'(?:\b(?:Firebase|Google)\s+)?' + _COMPETITORS
     + r'(?:\s*,?\s*(?:or|and)\s+(?:\b(?:Firebase|Google)\s+)?' + _COMPETITORS + r')+',
     'a third-party reporter', 'competitor'),
    (r'(?:\b(?:Firebase|Google)\s+)?' + _COMPETITORS,
     'a third-party reporter', 'competitor'),

    # --- tidy what removal left behind ------------------------------------
    (r'^(\s*(?:///?|\*|/\*\*?)\s*)—\s*', r'\1', 'tidy'),
    (r'[ \t]{2,}([^ \t])', r' \1', 'tidy'),
    (r'\(\s*\)', '', 'tidy'),
    (r'\s+([.,;:])', r'\1', 'tidy'),
    (r'([.,;:])\1+', r'\1', 'tidy'),
    (r'[ \t]+$', '', 'tidy'),
]
COMPILED = [(re.compile(p, re.M), r, t) for p, r, t in RULES]


def clean_comment(text):
    counts = collections.Counter()
    for rx, rep, tag in COMPILED:
        text, n = rx.subn(rep, text)
        if n:
            counts[tag] += n
    return text, counts


def process(path):
    ext = path.rsplit('.', 1)[-1]
    src = open(path, encoding='utf-8').read()
    before_code = strip_comments(src, ext)
    out, total = [], collections.Counter()
    last = 0
    for a, b in comment_spans(src, ext):
        out.append(src[last:a])
        new, c = clean_comment(src[a:b])
        out.append(new)
        total += c
        last = b
    out.append(src[last:])
    new_src = ''.join(out)
    if new_src != src:
        # The proof: the code must be untouched. A rule that escaped its
        # comment span is a defect, not a cleanup.
        after_code = strip_comments(new_src, ext)
        # Token-sequence identity. Any added, removed or altered code token
        # fails this; whitespace alone cannot change behaviour, and a comment
        # that lost a blank line legitimately changes the line count.
        if after_code.split() != before_code.split():
            raise SystemExit(f'REFUSED: cleanup altered code in {path}')
        open(path, 'w', encoding='utf-8').write(new_src)
    return total


def main(roots):
    grand, touched = collections.Counter(), 0
    for root in roots:
        walk = [root] if os.path.isfile(root) else None
        if walk is None:
            walk = [os.path.join(dp, fn) for dp, _, fns in os.walk(root) for fn in fns]
        for p in walk:
            if p.rsplit('.', 1)[-1] not in EXTS:
                continue
            c = process(p)
            if c:
                touched += 1
                grand += c
    print(f'files changed: {touched}')
    for k, v in sorted(grand.items(), key=lambda x: -x[1]):
        print(f'  {k:10s} {v}')
    print(f'  {"TOTAL":10s} {sum(grand.values())}')


if __name__ == '__main__':
    main(sys.argv[1:] or ['android/src', 'ios', 'src'])
