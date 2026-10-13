#!/usr/bin/env python3
"""Reject incomplete/failed CI evidence, independently of Maven's exit status."""
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def passed_cases(tree):
    cases = list(tree.iter('testcase'))
    require(cases, 'Report contains no executed test cases.')
    for suite in (node for node in tree.iter() if node.tag in ('testsuite', 'testsuites')):
        for field in ('failures', 'errors', 'skipped'):
            require(int(suite.get(field, '0')) == 0, f'Report has {field}: {suite.get("name", "unknown suite")}')
    for case in cases:
        require(not any(case.find(field) is not None for field in ('failure', 'error', 'skipped')), 'A test did not pass.')
    return cases


def verify(root):
    root = Path(root)
    required = set()
    mysql = set()
    for source in (root / 'src/test/java').rglob('*Test.java'):
        text = source.read_text()
        if re.search(r'@(Test|ParameterizedTest)\b', text):
            package = re.search(r'^package\s+([\w.]+);', text, re.MULTILINE)
            require(package is not None, f'Missing test package: {source.name}')
            name = package.group(1) + '.' + source.stem
            required.add(name)
            if 'account.mysql.url' in text:
                mysql.add(name)
    require(required and mysql, 'Required backend/MySQL suites were not discovered.')
    suites = {}
    total = 0
    for report in (root / 'target/surefire-reports').glob('TEST-*.xml'):
        tree = ET.parse(report).getroot()
        cases = passed_cases(tree)
        name = tree.get('name')
        require(name not in suites, 'Duplicate backend suite reports.')
        suites[name] = cases
        total += len(cases)
    require(required.issubset(suites), 'Missing backend suites: ' + ', '.join(sorted(required - suites.keys())))
    smoke = suites.get('com.mmu.schedulingapp.Java25ApplicationSmokeTest', [])
    require(any(case.get('name') == 'browserLoginCsrfRenderingAndLogout' for case in smoke), 'Browser journey did not execute.')
    browser = json.loads((root / '.ci-artifacts/browser/journey-complete.json').read_text())
    require(browser.get('complete') is True, 'Browser journey did not complete.')
    frontend = ET.parse(root / 'target/frontend-reports/vitest.xml').getroot()
    frontend_cases = passed_cases(frontend)
    names = [suite.get('name', '') for suite in frontend.iter('testsuite')]
    names += [case.get('classname', '') for case in frontend_cases]
    required_frontend = [source.relative_to(root / 'frontend').as_posix() for source in (root / 'frontend/src').rglob('*') if source.name.endswith(('.test.ts', '.test.tsx'))]
    require(required_frontend, 'Frontend test files were not discovered.')
    for source in required_frontend:
        require(any(name.replace('\\', '/').endswith(source) for name in names), f'Missing frontend suite: {source}')
    jar = root / 'target/schedulingApp-0.0.1-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        entries = archive.namelist()
        require('BOOT-INF/classes/static/app/index.html' in entries, 'Packaged frontend HTML is missing.')
        require(any(name.startswith('BOOT-INF/classes/static/app/assets/') and name.endswith('.js') for name in entries), 'Packaged frontend JavaScript is missing.')
        require(any(name.startswith('BOOT-INF/classes/static/app/assets/') and name.endswith('.css') for name in entries), 'Packaged frontend CSS is missing.')
        require('BOOT-INF/classes/com/mmu/schedulingapp/SchedulingAppApplication.class' in entries, 'Packaged backend is missing.')
    return {'backendTests': total, 'frontendTests': len(frontend_cases), 'mysqlSuites': len(mysql), 'browserComplete': True, 'packagedApplication': True}


if __name__ == '__main__':
    try:
        result = verify(Path(__file__).resolve().parents[1])
        output = Path('.ci-artifacts/coverage-summary.json')
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as failure:
        print(f'CI evidence verification failed: {failure}', file=sys.stderr)
        sys.exit(1)
