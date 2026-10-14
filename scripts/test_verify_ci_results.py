import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('checker', Path(__file__).with_name('verify-ci-results.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class CoverageGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.write('src/test/java/example/MySqlTest.java', 'package example;\nclass MySqlTest { @Test void test() {} } // account.mysql.url')
        self.write('src/test/java/com/mmu/schedulingapp/Java25ApplicationSmokeTest.java', 'package com.mmu.schedulingapp;\nclass Java25ApplicationSmokeTest { @Test void browserLoginCsrfRenderingAndLogout() {} }')
        self.write('frontend/src/example.test.ts', '')
        self.write('target/surefire-reports/TEST-mysql.xml', self.suite('example.MySqlTest', 'test'))
        self.write('target/surefire-reports/TEST-browser.xml', self.suite('com.mmu.schedulingapp.Java25ApplicationSmokeTest', 'browserLoginCsrfRenderingAndLogout'))
        self.write('target/frontend-reports/vitest.xml', self.suite('src/example.test.ts', 'test'))
        self.write('.ci-artifacts/browser/journey-complete.json', json.dumps({'complete': True}))
        with zipfile.ZipFile(self.root / 'target/schedulingApp-0.0.1-SNAPSHOT.jar', 'w') as archive:
            for name in ['BOOT-INF/classes/static/app/index.html', 'BOOT-INF/classes/static/app/assets/app.js', 'BOOT-INF/classes/static/app/assets/app.css', 'BOOT-INF/classes/com/mmu/schedulingapp/SchedulingAppApplication.class', 'BOOT-INF/classes/db/migration/V1__initial_schema.sql', 'BOOT-INF/classes/db/schema-v1-contract.json', 'BOOT-INF/classes/application-schema-managed.yml', 'BOOT-INF/lib/flyway-core-fixture.jar', 'BOOT-INF/lib/flyway-mysql-fixture.jar']:
                archive.writestr(name, 'fixture')

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def suite(self, name, test, result=''):
        return f'<testsuite name="{name}" tests="1" failures="0" errors="0" skipped="0"><testcase name="{test}">{result}</testcase></testsuite>'

    def test_complete_evidence_passes(self):
        self.assertEqual(checker.verify(self.root)['mysqlSuites'], 1)

    def test_missing_mysql_report_fails(self):
        (self.root / 'target/surefire-reports/TEST-mysql.xml').unlink()
        with self.assertRaises(ValueError): checker.verify(self.root)

    def test_skipped_test_fails_even_with_zero_suite_counter(self):
        self.write('target/surefire-reports/TEST-mysql.xml', self.suite('example.MySqlTest', 'test', '<skipped/>'))
        with self.assertRaises(ValueError): checker.verify(self.root)

    def test_failed_test_fails(self):
        self.write('target/surefire-reports/TEST-mysql.xml', self.suite('example.MySqlTest', 'test', '<failure/>'))
        with self.assertRaises(ValueError): checker.verify(self.root)

    def test_missing_browser_method_fails(self):
        self.write('target/surefire-reports/TEST-browser.xml', self.suite('com.mmu.schedulingapp.Java25ApplicationSmokeTest', 'other'))
        with self.assertRaises(ValueError): checker.verify(self.root)

    def test_missing_frontend_suite_fails(self):
        self.write('target/frontend-reports/vitest.xml', self.suite('src/other.test.ts', 'test'))
        with self.assertRaises(ValueError): checker.verify(self.root)

    def test_missing_browser_completion_fails(self):
        self.write('.ci-artifacts/browser/journey-complete.json', '{"complete":false}')
        with self.assertRaises(ValueError): checker.verify(self.root)

    def test_missing_packaged_frontend_fails(self):
        with zipfile.ZipFile(self.root / 'target/schedulingApp-0.0.1-SNAPSHOT.jar', 'w') as archive: archive.writestr('META-INF/MANIFEST.MF', '')
        with self.assertRaises(ValueError): checker.verify(self.root)

    def test_missing_migration_resource_fails(self):
        path = self.root / 'target/schedulingApp-0.0.1-SNAPSHOT.jar'
        with zipfile.ZipFile(path) as archive:
            entries = {name: archive.read(name) for name in archive.namelist() if name != 'BOOT-INF/classes/db/migration/V1__initial_schema.sql'}
        with zipfile.ZipFile(path, 'w') as archive:
            for name, content in entries.items(): archive.writestr(name, content)
        with self.assertRaisesRegex(ValueError, 'Packaged migration resource'): checker.verify(self.root)

    def test_missing_migration_dependency_fails(self):
        path = self.root / 'target/schedulingApp-0.0.1-SNAPSHOT.jar'
        with zipfile.ZipFile(path) as archive:
            entries = {name: archive.read(name) for name in archive.namelist() if name != 'BOOT-INF/lib/flyway-mysql-fixture.jar'}
        with zipfile.ZipFile(path, 'w') as archive:
            for name, content in entries.items(): archive.writestr(name, content)
        with self.assertRaisesRegex(ValueError, 'Packaged migration dependency'): checker.verify(self.root)

    def test_root_frontend_error_counter_fails(self):
        self.write('target/frontend-reports/vitest.xml', '<testsuites errors="1">' + self.suite('src/example.test.ts', 'test') + '</testsuites>')
        with self.assertRaises(ValueError): checker.verify(self.root)


if __name__ == '__main__':
    unittest.main()
