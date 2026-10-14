import datetime as dt
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('dependencies', Path(__file__).with_name('check-dependencies.py'))
gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)
TODAY = dt.date(2026, 10, 14)
PACKAGE = {'ecosystem': 'Maven', 'name': 'example:library', 'version': '1.0', 'contexts': ['maven:runtime']}
FINDING = {**PACKAGE, 'id': 'GHSA-example', 'withdrawn': None}
DETAIL = {'id': FINDING['id'], 'affected': [{'package': {'ecosystem': 'Maven', 'name': PACKAGE['name']}}]}


def policy():
    return {'exceptions': [{**FINDING, 'reason': 'Reviewed feature is not reachable in this application.',
                            'owner': 'maintainer', 'reference': 'https://example.test/review/1', 'expires': '2026-10-21'}]}


class DependencySecurityTests(unittest.TestCase):
    def test_inventory_covers_maven_scopes_and_both_lockfiles_without_application_roots(self):
        tree = {'children': [{'groupId': 'example', 'artifactId': 'library', 'version': '1.0', 'scope': scope}
                             for scope in ('runtime', 'test', 'provided')]}
        locks = {source: {'lockfileVersion': 3, 'packages': {'': {'name': 'private-application'},
                 'node_modules/@scope/name': {'version': '2.0.0', 'dev': True}}} for source in ('frontend', 'tests/browser')}
        packages, counts = gate.inventory(tree, locks)
        self.assertEqual(len(packages), 2)
        self.assertEqual(packages[0]['contexts'], ['maven:provided', 'maven:runtime', 'maven:test'])
        self.assertIn('browser-test', packages[1]['contexts'])
        self.assertNotIn('private-application', json.dumps(packages))
        self.assertEqual(counts['frontend'], 1)

    def test_empty_missing_linked_or_wrong_lock_inputs_fail(self):
        tree = {'children': [{'groupId': 'g', 'artifactId': 'a', 'version': '1', 'scope': 'runtime'}]}
        for locks in ({}, {'frontend': {'lockfileVersion': 2}},
                      {s: {'lockfileVersion': 3, 'packages': {}} for s in ('frontend', 'tests/browser')},
                      {s: {'lockfileVersion': 3, 'packages': {'node_modules/a': {'link': True}}} for s in ('frontend', 'tests/browser')}):
            with self.subTest(locks=locks), self.assertRaises(gate.CheckError): gate.inventory(tree, locks)
        with self.assertRaises(gate.CheckError): gate.inventory({}, {})

    def test_packaged_libraries_are_matched_and_marked(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'app.jar'
            tree = {'children': [{'groupId': 'example', 'artifactId': 'library', 'version': '1.0', 'type': 'jar'}]}
            packages = [dict(PACKAGE)]
            with zipfile.ZipFile(jar, 'w') as output: output.writestr('BOOT-INF/lib/library-1.0.jar', '')
            self.assertEqual(gate.packaged_inventory(tree, jar, packages), 1)
            self.assertIn('packaged-runtime', packages[0]['contexts'])
            with zipfile.ZipFile(jar, 'w') as output: output.writestr('BOOT-INF/lib/library-0.9.jar', '')
            with self.assertRaises(gate.CheckError): gate.packaged_inventory(tree, jar, packages)

    def test_empty_or_development_runtime_artifacts_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'app.jar'
            for filename in (None, 'lombok-1.0.jar'):
                tree = {'children': [{'groupId': 'g', 'artifactId': 'lombok', 'version': '1.0', 'type': 'jar'}]}
                with zipfile.ZipFile(jar, 'w') as output:
                    if filename: output.writestr('BOOT-INF/lib/' + filename, '')
                with self.assertRaises(gate.CheckError): gate.packaged_inventory(tree, jar, [])

    def test_boot_added_tools_are_identified_and_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            import io
            tool_bytes = io.BytesIO()
            with zipfile.ZipFile(tool_bytes, 'w') as tool:
                tool.writestr('META-INF/MANIFEST.MF', 'Automatic-Module-Name: spring.boot.jarmode.tools\nImplementation-Title: Spring Boot Jarmode Tools\nImplementation-Version: 4.1.1\n')
            jar = Path(directory) / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as output:
                output.writestr('BOOT-INF/lib/spring-boot-jarmode-tools-4.1.1.jar', tool_bytes.getvalue())
            packages = [{'ecosystem': 'Maven', 'name': 'org.springframework.boot:spring-boot', 'version': '4.1.1', 'contexts': ['maven:compile']}]
            self.assertEqual(gate.packaged_inventory({}, jar, packages), 1)
            self.assertEqual(packages[-1]['name'], 'org.springframework.boot:spring-boot-jarmode-tools')
            self.assertIn('packaged-runtime', packages[-1]['contexts'])

    def test_complete_empty_osv_results_pass(self):
        findings, details = gate.query([PACKAGE], lambda path, data: {'results': [{}]})
        self.assertEqual(findings, []); self.assertEqual(details, {})

    def test_truncated_error_and_malformed_responses_fail(self):
        for response in ({}, {'results': []}, {'results': [{'error': 'failed'}]}, {'results': [{'message': 'unavailable'}]}, {'results': [{'vulns': None}]},
                         {'results': [{'vulns': [{'id': '../unexpected'}]}]}):
            with self.subTest(response=response), self.assertRaises(gate.CheckError):
                gate.query([PACKAGE], lambda path, data: response)

    def test_pagination_preserves_matches_and_fetches_details_once(self):
        calls = []
        def api(path, data=None):
            calls.append((path, data))
            if path.startswith('/v1/vulns/'): return DETAIL
            result = {'vulns': [{'id': FINDING['id']}]}
            if len([p for p, _ in calls if p == '/v1/querybatch']) == 1: result['next_page_token'] = 'next'
            return {'results': [result]}
        findings, records = gate.query([PACKAGE], api)
        self.assertEqual(len(findings), 1)
        self.assertEqual(len(records), 1)
        self.assertEqual(calls[-1][1]['queries'][0]['page_token'], 'next')

    def test_repeating_pagination_token_and_mismatched_details_fail(self):
        def repeated(path, data=None):
            return DETAIL if path.startswith('/v1/vulns/') else {'results': [{'next_page_token': 'again'}]}
        with self.assertRaises(gate.CheckError): gate.query([PACKAGE], repeated)
        def wrong(path, data=None):
            return {'id': FINDING['id'], 'affected': []} if path.startswith('/v1/vulns/') else {'results': [{'vulns': [{'id': FINDING['id']}]}]}
        with self.assertRaises(gate.CheckError): gate.query([PACKAGE], wrong)

    def test_network_failure_never_establishes_clean_evidence(self):
        with patch('urllib.request.OpenerDirector.open', side_effect=OSError('offline')):
            with self.assertRaises(gate.CheckError): gate.request('/v1/querybatch', {})
        self.assertIsNone(gate.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://elsewhere.test'))

    def test_no_exceptions_means_all_matches_require_review(self):
        self.assertEqual(gate.evaluate([FINDING], {'exceptions': []}, TODAY), [FINDING])

    def test_exact_reviewed_unexpired_exception_passes(self):
        self.assertEqual(gate.evaluate([FINDING], policy(), TODAY), [])

    def test_expired_far_future_wildcard_or_unexplained_exceptions_fail(self):
        for field, value in (('expires', '2026-10-14'), ('expires', '2027-10-14'), ('reason', ''),
                             ('version', '*'), ('contexts', ['maven:test']), ('owner', ''), ('owner', True), ('reference', ''), ('reference', 'https://')):
            with self.subTest(field=field):
                p = policy(); p['exceptions'][0][field] = value
                with self.assertRaises(gate.CheckError): gate.evaluate([FINDING], p, TODAY)

    def test_unused_and_duplicate_exceptions_fail(self):
        with self.assertRaises(gate.CheckError): gate.evaluate([], policy(), TODAY)
        p = policy(); p['exceptions'] *= 2
        with self.assertRaises(gate.CheckError): gate.evaluate([FINDING], p, TODAY)

    def test_withdrawn_advisory_is_retained_but_not_a_blocker(self):
        self.assertEqual(gate.evaluate([{**FINDING, 'withdrawn': '2026-10-01T00:00:00Z'}], {'exceptions': []}, TODAY), [])

    def test_failure_overwrites_a_previous_clean_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); output = root / 'reports'; output.mkdir()
            (output / 'summary.json').write_text('{"complete":true}')
            with patch.object(gate, 'ROOT', root), patch.object(gate, 'OUTPUT', output), patch('sys.stderr'):
                self.assertEqual(gate.main(), 1)
            self.assertFalse(json.loads((output / 'summary.json').read_text())['complete'])
