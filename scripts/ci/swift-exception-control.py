#!/usr/bin/env python3
"""Compile and execute the unsafe exception-name mutation after the positive probe."""
from pathlib import Path
import resource
import subprocess

resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
out = Path('ci-results/swift')
source = (out / 'main.swift').read_text()
safe = 'return known.contains(raw) ? raw : "NSException"'
assert source.count(safe) == 1
mutant = out / 'unsafe-exception.swift'
mutant.write_text(source.replace(safe, 'return raw'))
binary = out / 'unsafe-exception'
subprocess.run(['swiftc', str(mutant), '-o', str(binary)], check=True)
run = subprocess.run([str(binary)], capture_output=True, text=True)
(out / 'unsafe-exception.log').write_text(run.stdout + run.stderr)
assert run.returncode != 0 and 'exception name privacy leak' in run.stderr, (run.returncode, run.stderr)
print('PASS compiled unsafe exception-name mutation rejected by executed privacy assertion')
