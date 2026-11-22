#!/usr/bin/env python3
"""Compile and execute the unsafe exception-name mutation after the positive probe."""
from pathlib import Path
import resource
import subprocess

resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
out = Path('ci-results/swift')
source = (out / 'main.swift').read_text()
cases = [
    ('unsafe-suspension', '!active || epoch != lastEpoch || lastSeen != heartbeat', 'lastSeen != heartbeat', 'suspension counted as hang'),
    ('unsafe-handler-owner', 'installed && current == ours', 'installed', 'foreign exception handler overwritten'),
    ('unsafe-watchdog', 'active == token', 'active != nil', 'retired watchdog revived after reinstall'),
    ('unsafe-exception', 'return known.contains(raw) ? raw : "NSException"', 'return raw', 'exception name privacy leak'),
    ('unsafe-hierarchy', '65_536 - 512 - IdentityPolicy.userIdMax * 6', '65_536 - 512', 'hierarchy identity growth exceeded budget'),
]
for name, safe, unsafe, message in cases:
    assert source.count(safe) == 1
    mutant = out / (name + '.swift')
    mutant.write_text(source.replace(safe, unsafe))
    binary = out / name
    subprocess.run(['swiftc', str(mutant), '-o', str(binary)], check=True)
    run = subprocess.run([str(binary)], capture_output=True, text=True)
    (out / (name + '.log')).write_text(run.stdout + run.stderr)
    assert run.returncode != 0 and message in run.stderr, (run.returncode, run.stderr)
    print('PASS compiled ' + name + ' mutation rejected by executed assertion')
