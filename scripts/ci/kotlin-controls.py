#!/usr/bin/env python3
"""A compilation failure cannot satisfy the intentional mutation control."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = root / 'ci-results/kotlin'
out.mkdir(parents=True, exist_ok=True)
compiler = root / 'scripts/ci/android/build/probe-compiler'
probe = root / 'scripts/tests/pr3-regressions.py'
for name, extra in [('positive', []), ('stale-identity', ['--mutate-identity'])]:
    result = subprocess.run([sys.executable, str(probe), '--compiler-lib', str(compiler),
                             '--out', str(out / name), *extra], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (out / f'{name}.log').write_text(result.stdout)
    print(result.stdout)
    if name == 'positive':
        assert result.returncode == 0, 'positive probe failed'
        assert 'PASS controlled capture/identify interleaving' in result.stdout
    else:
        assert result.returncode != 0, 'stale-identity mutation escaped detection'
        assert (out / name / 'probe.jar').is_file(), 'mutation did not compile'
        assert 'java.lang.IllegalStateException: late capture reverted identity' in result.stdout, 'wrong failure'
print('PASS positive admission controls and executed stale-identity mutation rejection')
