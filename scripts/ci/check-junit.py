#!/usr/bin/env python3
"""Require every committed native regression to execute, without skips."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
reports = root / 'android/build/test-results/testDebugUnitTest'
classes = ('Pr3PipelineRegressionTest', 'Pr3HostBoundaryTest', 'Pr3InteractionAdmissionTest')
total = 0
for name in classes:
    source = (root / 'scripts/tests' / (name + '.kt')).read_text()
    expected = set(re.findall(r'@Test\s+fun\s+(\w+)\s*\(', source))
    assert expected, f'no test methods discovered: {name}'
    files = list(reports.glob(f'TEST-*.{name}.xml'))
    assert len(files) == 1, f'missing/ambiguous JUnit report: {name}'
    suite = ET.parse(files[0]).getroot()
    cases = suite.findall('testcase')
    actual = {c.attrib['name'] for c in cases}
    assert actual == expected and len(cases) == len(expected), (name, expected, actual)
    assert not any(c.find(tag) is not None for c in cases for tag in ('failure', 'error', 'skipped')), name
    print(f'{name}: {len(cases)} executed, zero failures/errors/skips')
    total += len(cases)
print(f'TOTAL: {total} native JVM regressions executed')
