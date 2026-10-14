import copy
import importlib.util
import json
import os
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('merge_gate', Path(__file__).with_name('verify-github-merge-gate.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SHA = 'a' * 40


def complete():
    return {
        'repository': gate.REPOSITORY, 'branch': 'main', 'commit_sha': SHA,
        'runs': [{'id': 42, 'run_number': 7, 'run_attempt': 2, 'head_sha': SHA,
                  'head_branch': 'main', 'path': gate.WORKFLOW, 'event': 'push',
                  'status': 'completed', 'conclusion': 'success'}],
        'run_id': 42, 'run_attempt': 2,
        'jobs': [{'name': gate.CHECK, 'head_sha': SHA, 'status': 'completed', 'conclusion': 'success',
                  'steps': [{'name': name, 'status': 'completed', 'conclusion': 'success'}
                            for name in gate.REQUIRED_STEPS]}],
        'artifacts': [{'name': name, 'expired': False, 'size_in_bytes': 512}
                      for name in ('test-results-42-2', f'application-{SHA}-2')],
        'actions_app': {'slug': 'github-actions', 'id': 15368},
        'protection': {'enforce_admins': {'enabled': True}, 'required_pull_request_reviews': {},
                       'required_status_checks': {'strict': True, 'checks': [{'context': gate.CHECK, 'app_id': 15368}]},
                       'allow_force_pushes': {'enabled': False}, 'allow_deletions': {'enabled': False}}
    }


class MergeGateTests(unittest.TestCase):
    def test_current_successful_run_with_enforced_policy_passes(self):
        result = gate.verify(complete())
        self.assertTrue(result['verified'])
        self.assertEqual(result['commitSha'], SHA)

    def test_access_failure_is_not_empty_success(self):
        for field in ('runs', 'jobs', 'artifacts', 'protection'):
            for status in (403, 404):
                with self.subTest(field=field, status=status):
                    evidence = complete(); evidence[field] = {'http_status': status}
                    with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_stale_wrong_branch_workflow_or_manual_run_fails(self):
        for field, value in (('head_sha', 'b' * 40), ('head_branch', 'other'),
                             ('path', '.github/workflows/other.yml'), ('event', 'workflow_dispatch')):
            with self.subTest(field=field):
                evidence = complete(); evidence['runs'][0][field] = value
                with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_latest_failed_or_pending_attempt_cannot_reuse_older_success(self):
        for status, conclusion in (('completed', 'failure'), ('in_progress', None), ('completed', 'cancelled')):
            with self.subTest(status=status, conclusion=conclusion):
                evidence = complete(); latest = copy.deepcopy(evidence['runs'][0])
                latest.update(run_attempt=3, status=status, conclusion=conclusion)
                evidence['runs'].append(latest)
                with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_jobs_cannot_come_from_an_older_attempt(self):
        evidence = complete(); evidence['run_attempt'] = 1
        with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_missing_duplicate_stale_or_skipped_job_fails(self):
        for kind in ('missing', 'duplicate', 'stale', 'skipped'):
            with self.subTest(kind=kind):
                evidence = complete()
                if kind == 'missing': evidence['jobs'] = []
                elif kind == 'duplicate': evidence['jobs'].append(copy.deepcopy(evidence['jobs'][0]))
                elif kind == 'stale': evidence['jobs'][0]['head_sha'] = 'b' * 40
                else: evidence['jobs'][0]['conclusion'] = 'skipped'
                with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_every_required_step_must_run_and_pass(self):
        for index in range(len(gate.REQUIRED_STEPS)):
            for conclusion in ('skipped', 'failure', None):
                with self.subTest(index=index, conclusion=conclusion):
                    evidence = complete(); evidence['jobs'][0]['steps'][index]['conclusion'] = conclusion
                    with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_absent_expired_empty_or_previous_attempt_artifacts_fail(self):
        for kind in ('missing', 'expired', 'empty', 'old'):
            with self.subTest(kind=kind):
                evidence = complete()
                if kind == 'missing': evidence['artifacts'].pop()
                elif kind == 'expired': evidence['artifacts'][0]['expired'] = True
                elif kind == 'empty': evidence['artifacts'][0]['size_in_bytes'] = 0
                else: evidence['artifacts'][0]['name'] = 'test-results-42-1'
                with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_missing_protection_and_admin_or_pull_request_bypass_fail(self):
        for kind in ('absent', 'admin', 'pr', 'bypass'):
            with self.subTest(kind=kind):
                evidence = complete()
                if kind == 'absent': evidence['protection'] = None
                elif kind == 'admin': evidence['protection']['enforce_admins']['enabled'] = False
                elif kind == 'pr': evidence['protection']['required_pull_request_reviews'] = None
                else: evidence['protection']['required_pull_request_reviews']['bypass_pull_request_allowances'] = {'users': [{}]}
                with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_unpinned_wrong_source_or_wrong_check_fails(self):
        for change in ({'app_id': None}, {'app_id': 123}, {'context': 'Other check'}):
            with self.subTest(change=change):
                evidence = complete(); evidence['protection']['required_status_checks']['checks'][0].update(change)
                with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_loose_status_checks_force_pushes_and_deletions_fail(self):
        for setting in ('strict', 'allow_force_pushes', 'allow_deletions'):
            with self.subTest(setting=setting):
                evidence = complete()
                if setting == 'strict': evidence['protection']['required_status_checks']['strict'] = False
                else: evidence['protection'][setting]['enabled'] = True
                with self.assertRaises(gate.GateError): gate.verify(evidence)

    def test_wrong_repository_and_malformed_evidence_fail(self):
        evidence = complete(); evidence['repository'] = 'other/repository'
        with self.assertRaises(gate.GateError): gate.verify(evidence)
        with self.assertRaises(gate.GateError): gate.verify([])

    def test_pagination_collects_all_pages_and_rejects_truncation(self):
        api = gate.GitHub('private-token')
        with patch.object(api, 'get', side_effect=[{'jobs': [{}] * 100}, {'jobs': [{}]}]):
            self.assertEqual(len(api.pages('/repos/' + gate.REPOSITORY + '/actions/runs/42/jobs', 'jobs')), 101)
        with patch.object(api, 'get', return_value={'jobs': [{}] * 100}):
            with self.assertRaises(gate.GateError): api.pages('/repos/' + gate.REPOSITORY + '/jobs', 'jobs')

    def test_collector_detects_main_moving(self):
        class Moving:
            def get(self, path):
                if path.endswith('/branches/main'):
                    self.reads = getattr(self, 'reads', 0) + 1
                    return {'commit': {'sha': SHA if self.reads == 1 else 'b' * 40}}
                return {}
            def pages(self, path, key): return []
        with self.assertRaises(gate.GateError): gate.collect(Moving())

    def test_api_only_uses_get_and_does_not_leak_credentials_on_errors(self):
        api = gate.GitHub('private-token')
        error = urllib.error.HTTPError('https://api.github.com', 403, 'private-server-detail', {}, None)
        with patch.object(api.opener, 'open', side_effect=error) as opened:
            self.assertEqual(api.get('/repos/' + gate.REPOSITORY + '/branches/main'), {'http_status': 403})
            self.assertEqual(opened.call_args.args[0].get_method(), 'GET')
        with self.assertRaises(gate.GateError): api.get('/repos/other/repository/branches/main')
        self.assertIsNone(gate.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example'))

    def test_policy_template_matches_the_verifier_without_requiring_another_reviewer(self):
        policy = json.loads((Path(__file__).parents[1] / '.github/policies/main-branch-protection.json').read_text())
        evidence = complete()
        evidence['protection'] = {**policy, 'enforce_admins': {'enabled': policy['enforce_admins']},
                                  'allow_force_pushes': {'enabled': policy['allow_force_pushes']},
                                  'allow_deletions': {'enabled': policy['allow_deletions']}}
        self.assertTrue(gate.verify(evidence)['verified'])
        self.assertEqual(policy['required_pull_request_reviews']['required_approving_review_count'], 0)

    def test_cli_distinguishes_offline_evidence_and_returns_failure_for_access_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'evidence.json'; path.write_text(json.dumps(complete()))
            with patch('sys.argv', ['gate', '--evidence', str(path)]), patch('sys.stdout') as output:
                self.assertEqual(gate.main(), 0)
                self.assertIn('offline-snapshot', ''.join(call.args[0] for call in output.write.call_args_list))
            evidence = complete(); evidence['runs'] = {'http_status': 403}; path.write_text(json.dumps(evidence))
            with patch('sys.argv', ['gate', '--evidence', str(path)]), patch('sys.stderr') as output:
                self.assertEqual(gate.main(), 1)
                self.assertNotIn('private-token', str(output.write.call_args_list))
            with patch.dict(os.environ, {}, clear=True), patch('sys.argv', ['gate', '--fetch']), patch('sys.stderr'):
                self.assertEqual(gate.main(), 1)
