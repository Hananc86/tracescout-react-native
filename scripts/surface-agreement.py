#!/usr/bin/env python3
"""The JavaScript surface is declared in four places. They must name the same
methods -- and the check is by NAME, never by count.

The four declarations:

  ios/RNBridge/TraceScoutRnModule.m        RCT_EXTERN_METHOD  -- what the
                                           Objective-C bridge actually exports
                                           to JavaScript on iOS
  ios/RNBridge/TraceScoutRnModule.swift    @objc(...) selectors -- the Swift
                                           implementations those exports reach
  android/.../react/TraceScoutRnModule.kt  @ReactMethod -- the Android surface
  src/index.ts                             interface NativeBridge -- what a
                                           TypeScript consumer may call

WHY BY NAME AND NOT BY COUNT. A count has no name to add. When a reviewed
method joined the native surface, every declaration that NAMED its methods
gained it in a diff a reviewer could read; the declarations that carried only
the number eleven stayed green on a surface that had twelve, and the
disagreement surfaced later and elsewhere. So this script never writes a
number down: the expectation is derived from the sources themselves, and the
only assertion is that all four agree.

A WIDENING IS STILL A REVIEW. Agreement is not authorization -- four
declarations can be widened together. What this guarantees is that a widening
cannot happen in some of them and be missed in the rest.

exit 0 = the four agree | 1 = they do not | 2 = COULD_NOT_RUN, never a pass.
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else '.'

M = 'ios/RNBridge/TraceScoutRnModule.m'
SWIFT = 'ios/RNBridge/TraceScoutRnModule.swift'
KT = 'android/src/main/java/com/tracescout/rn/react/TraceScoutRnModule.kt'
TS = 'src/index.ts'

# A floor, not an expectation. It exists so that a derivation which silently
# stopped matching cannot be read as four surfaces agreeing on nothing.
FLOOR = 8


def cnr(msg):
    print(f'COULD_NOT_RUN: {msg}')
    raise SystemExit(2)


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        cnr(f'missing declaration: {rel}')
    return open(p, encoding='utf-8').read()


src_m, src_swift, src_kt, src_ts = (read(f) for f in (M, SWIFT, KT, TS))

# --- the four derivations -------------------------------------------------
# Whitespace is tolerated everywhere a compiler tolerates it. A matcher that
# demands none is bypassed by one space.

objc = sorted(set(re.findall(r'RCT_EXTERN_METHOD\s*\(\s*([A-Za-z0-9_]+)', src_m)))

# Method selectors are members, so they are indented; the type's own
# `@objc(TraceScoutRn)` sits at column zero and is not a method.
swift = sorted(set(re.findall(r'^[ \t]+@objc\(\s*([A-Za-z0-9_]+)', src_swift, re.M)))

kt = sorted(set(re.findall(r'@ReactMethod\s+fun\s+([A-Za-z0-9_]+)', src_kt)))

ts_body = re.search(r'interface\s+NativeBridge\s*\{(.*?)\n\}', src_ts, re.S)
if not ts_body:
    cnr(f'{TS} has no `interface NativeBridge {{ ... }}` to read')
ts = sorted(set(re.findall(r'^\s{2}([A-Za-z0-9_]+)\s*\(', ts_body.group(1), re.M)))

SURFACES = [('objc', objc), ('swift', swift), ('kotlin', kt), ('typescript', ts)]

rows, bad = [], 0


def check(tag, ok, msg):
    global bad
    rows.append(f"  {'ok  ' if ok else 'FAIL'} [{tag}] {msg}")
    if not ok:
        bad += 1


# --- non-vacuity first ----------------------------------------------------
# Four empty sets agree. Establish that each derivation found something before
# any agreement row is allowed to mean anything.
for i, (name, s) in enumerate(SURFACES, 1):
    check(f'A{i}', len(s) >= FLOOR,
          f'{name} surface is non-vacuous: {len(s)} methods (floor {FLOOR})')

# --- the agreement --------------------------------------------------------
ref_name, ref = SURFACES[0]
for i, (name, s) in enumerate(SURFACES[1:], 1):
    missing = sorted(set(ref) - set(s))
    extra = sorted(set(s) - set(ref))
    detail = ''
    if missing:
        detail += f' absent from {name}: {missing}'
    if extra:
        detail += f' present only in {name}: {extra}'
    check(f'B{i}', not missing and not extra,
          f'{name} names the same methods as {ref_name}.{detail}')

# --- the closed macro form ------------------------------------------------
# Naming the alternate macros to reject by hand is the same defect as naming
# the methods to allow by hand. So: scan for ANY export-shaped macro and
# require every one of them to be the single reviewed form.
macros = re.findall(r'\bRCT_(?:EXTERN|EXPORT|REMAP)_?\w*METHOD\s*\(', src_m)
alien = sorted({m for m in macros if not re.fullmatch(r'RCT_EXTERN_METHOD\s*\(', m)})
check('C1', not alien and 'RCT_EXPORT_MODULE' not in src_m,
      f'the .m exports through one reviewed macro form only; alien forms: {alien or "none"}')
check('C2', len(macros) == len(objc),
      f'the macro scan and the name derivation see the same number of exports '
      f'({len(macros)} vs {len(objc)}) -- neither is silently missing one')

# --- every declared method is reachable from the package's own wrapper ----
# A name in the interface that no exported function forwards is a method a
# consumer can see in the types and cannot call.
for n in ts:
    if not re.search(r'^export function\s+' + re.escape(n) + r'\s*\(', src_ts, re.M):
        check('D1', False, f'`{n}` is declared in NativeBridge but no exported wrapper calls it')
        break
else:
    check('D1', True, f'all {len(ts)} declared methods have an exported wrapper')

default_block = re.search(r'export default \{(.*?)\n\};', src_ts, re.S)

if not default_block:
    cnr(f'{TS} has no `export default {{ ... }}` to read')
in_default = set(re.findall(r'^\s{2}([A-Za-z0-9_]+),', default_block.group(1), re.M))
absent = sorted(set(ts) - in_default)
check('D2', not absent,
      f'every declared method is on the default export too; absent: {absent or "none"}')

# --- an exported method must have its ENABLEMENT wired --------------------
# Agreeing on names is not the same as being able to succeed. The bridge takes
# optional dependencies that default to null, and a method whose dependency was
# not supplied answers NOT_INTEGRATED on every call: it compiles, it carries
# its platform's export annotation, it agrees with every declaration above --
# and it cannot work. That exact defect shipped once already, which is why the
# iOS module's comment beside `applyUserId` reads "ITS ABSENCE WAS THE WHOLE
# DEFECT".
#
# So: derive from each bridge the dependencies whose absence yields
# NOT_INTEGRATED, and require the platform's module to supply every one.
KT_BRIDGE = 'android/src/main/java/com/tracescout/rn/bridge/RnBridge.kt'
SWIFT_BRIDGE = 'ios/TraceScoutRN/RnBridge.swift'
SWIFT_MODULE = 'ios/RNBridge/TraceScoutRnModule.swift'

needed_kt = sorted(set(re.findall(
    r'(\w+)\s*\?:\s*return\s+Outcome\.NOT_INTEGRATED', read(KT_BRIDGE))))
needed_swift = sorted(set(re.findall(
    r'guard\s+let\s+\w+\s*=\s*(\w+)\s+else\s*\{\s*return\s+\.notIntegrated',
    read(SWIFT_BRIDGE))))

check('F0', bool(needed_kt) and bool(needed_swift),
      f'the enablement derivation is non-vacuous: kotlin {needed_kt}, swift {needed_swift}')

# Kotlin supplies a named argument `x = ...`; Swift supplies `x: ...`.
missing_kt = [d for d in needed_kt
              if not re.search(r'\b' + re.escape(d) + r'\s*=', src_kt)]
missing_swift = [d for d in needed_swift
                 if not re.search(r'\b' + re.escape(d) + r'\s*:', read(SWIFT_MODULE))]
check('F1', not missing_kt,
      f'the Android module supplies every dependency whose absence is '
      f'NOT_INTEGRATED; missing: {missing_kt or "none"}')
check('F2', not missing_swift,
      f'the iOS module supplies every dependency whose absence is '
      f'NOT_INTEGRATED; missing: {missing_swift or "none"}')

# --- the README's API table is the fifth declaration ----------------------
# It is what a customer reads to learn the surface, so a method shipped but
# undocumented is a method they cannot know about. Containment, not equality:
# the table also lists package functions such as `isAvailable` that are not
# native bridge methods.
readme = read('README.md')
api = re.search(r'\n## API\n(.*?)\n## ', readme, re.S)
if not api:
    cnr('README.md has no `## API` section to read')
documented = set(re.findall(r'`([A-Za-z0-9_]+)[(`]', api.group(1)))
undocumented = sorted(set(ts) - documented)
check('E1', not undocumented,
      f'every method the package exports is in the README API table; '
      f'undocumented: {undocumented or "none"}')

print('=== SURFACES (derived; no count is written down anywhere above) ===')
for name, s in SURFACES:
    print(f'  {name:11s} {len(s):2d}  {" ".join(s)}')
print('=== CHECKS ===')
print('\n'.join(rows))
print(f'\nRESULT {len(rows) - bad} ok / {bad} failed')
raise SystemExit(1 if bad else 0)
