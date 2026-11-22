#!/usr/bin/env python3
"""Mutate checker inputs in memory; never edit the checkout under a build."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
checker = (root / 'scripts/surface-agreement.py').read_text()
android = 'android/src/main/java/com/tracescout/rn/react/TraceScoutRnModule.kt'
swift = 'ios/RNBridge/TraceScoutRnModule.swift'
module = (root / android).read_text()
start = module.index('    @ReactMethod fun identify(')
end = module.index('\n\n', start)
export = module[start:end]
baseline = subprocess.run([sys.executable, str(root / 'scripts/surface-agreement.py'), str(root)], capture_output=True, text=True)
assert baseline.returncode == 0, ('unmutated surface check failed', baseline.stdout, baseline.stderr)
cases = [
    ('android-null', android, 'applyUserId = { userId ->', 'applyUserId = null, ignored = { userId ->', 'F1'),
    ('ios-nil', swift, 'applyUserId: {', 'applyUserId: nil, ignored: {', 'F2'),
    ('commented-export', android, export, '/*' + export + '*/', 'B'),
    ('wrong-state-method', 'src/index.ts', 'return native.state()', 'return native.startSession()', 'D1'),
    ('wrong-argument-order', 'src/index.ts', "call('hostAttached', routeId, hostId, rootHandle)", "call('hostAttached', hostId, routeId, rootHandle)", 'D1'),
    ('wrong-method', 'src/index.ts', "call('identify', userId)", "call('startSession', userId)", 'D1'),
]
for name, target, before, after, check in cases:
    assert before in (root / target).read_text(), f'{name}: control target absent'
    read = "return open(p, encoding='utf-8').read()"
    replacement = f"return open(p, encoding='utf-8').read().replace({before!r}, {after!r}) if rel == {target!r} else open(p, encoding='utf-8').read()"
    assert read in checker
    run = subprocess.run([sys.executable, '-c', checker.replace(read, replacement), str(root)],
                         capture_output=True, text=True)
    assert run.returncode == 1 and f'FAIL [{check}' in run.stdout, (name, run.stdout, run.stderr)
    print(f'PASS {name}: intended surface check rejected mutation')
