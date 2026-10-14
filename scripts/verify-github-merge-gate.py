#!/usr/bin/env python3
"""Read-only GitHub CI/protection verification; never changes repository settings."""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPOSITORY = 'animitchel/delivery-scheduling-system'
CHECK = 'Verify application'
WORKFLOW = '.github/workflows/ci.yml'
REQUIRED_STEPS = ('Install locked browser tooling and Chromium',
                  'Verify application and required test coverage',
                  'Upload test reports and browser diagnostics', 'Upload verified application')


class GateError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise GateError(message)


def verify(evidence):
    require(isinstance(evidence, dict), 'Evidence must be a JSON object.')
    require(evidence.get('repository') == REPOSITORY, 'Evidence belongs to another repository.')
    require(evidence.get('branch') == 'main', 'Evidence must describe main.')
    sha = evidence.get('commit_sha', '')
    require(re.fullmatch(r'[0-9a-f]{40}', sha) is not None, 'Missing full main commit SHA.')
    for field in ('runs', 'jobs', 'artifacts', 'protection'):
        require(not isinstance(evidence.get(field), dict) or 'http_status' not in evidence[field],
                f'GitHub access/plan is blocked for {field}; absence of evidence is not success.')
    runs = [run for run in evidence.get('runs', [])
            if run.get('head_sha') == sha and run.get('head_branch') == 'main'
            and run.get('path') == WORKFLOW and run.get('event') == 'push']
    require(runs, 'No main-push CI run found for the current commit.')
    run = max(runs, key=lambda item: (item['run_number'], item['run_attempt'], item['id']))
    require(run.get('status') == 'completed' and run.get('conclusion') == 'success',
            'The latest current-commit CI run has not succeeded.')
    require(evidence.get('run_id') == run['id'] and evidence.get('run_attempt') == run['run_attempt'],
            'Jobs/artifacts were not collected for the latest run attempt.')
    jobs = [job for job in evidence.get('jobs', []) if job.get('name') == CHECK]
    require(len(jobs) == 1, 'Expected one unambiguous Verify application job.')
    job = jobs[0]
    require(job.get('head_sha') == sha and job.get('status') == 'completed'
            and job.get('conclusion') == 'success', 'The required job did not pass for current main.')
    for name in REQUIRED_STEPS:
        steps = [step for step in job.get('steps', []) if step.get('name') == name]
        require(len(steps) == 1 and steps[0].get('status') == 'completed'
                and steps[0].get('conclusion') == 'success', f'Required CI step did not execute successfully: {name}')
    names = (f"test-results-{run['id']}-{run['run_attempt']}",
             f"application-{sha}-{run['run_attempt']}")
    for name in names:
        matches = [artifact for artifact in evidence.get('artifacts', []) if artifact.get('name') == name]
        require(len(matches) == 1 and matches[0].get('expired') is False
                and matches[0].get('size_in_bytes', 0) > 0, f'Missing, expired or empty artifact: {name}')
    app = evidence.get('actions_app', {})
    require(app.get('slug') == 'github-actions' and type(app.get('id')) is int and app['id'] > 0,
            'GitHub Actions integration identity was not verified.')
    protection = evidence.get('protection') or {}
    require(protection.get('enforce_admins', {}).get('enabled') is True,
            'Classic branch protection is absent or permits administrator bypass.')
    reviews = protection.get('required_pull_request_reviews')
    require(isinstance(reviews, dict), 'Pull requests are not required.')
    bypass = reviews.get('bypass_pull_request_allowances', {})
    require(not any(bypass.get(kind) for kind in ('users', 'teams', 'apps')),
            'Pull-request bypass allowances require manual review.')
    status = protection.get('required_status_checks') or {}
    require(status.get('strict') is True, 'Current-base status checks are not required.')
    require(any(check.get('context') == CHECK and check.get('app_id') == app['id']
                for check in status.get('checks', [])), 'Verify application is not required from GitHub Actions.')
    for setting in ('allow_force_pushes', 'allow_deletions'):
        require(protection.get(setting, {}).get('enabled') is False, f'{setting} is not disabled.')
    return {'verified': True, 'repository': REPOSITORY, 'branch': 'main', 'commitSha': sha,
            'runId': run['id'], 'runAttempt': run['run_attempt'], 'requiredCheck': CHECK,
            'protectionKind': 'classic', 'artifacts': list(names)}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None  # Never forward repository credentials to another endpoint.


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def get(self, path):
        require(path.startswith('/repos/' + REPOSITORY + '/') or path == '/apps/github-actions',
                'API request outside the supported repository.')
        headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
        if path != '/apps/github-actions':
            headers['Authorization'] = 'Bearer ' + self.token
        request = urllib.request.Request('https://api.github.com' + path, headers=headers)
        try:
            with self.opener.open(request, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            return {'http_status': error.code}  # No token, headers or server body in diagnostics.

    def pages(self, path, key):
        result = []
        for page in range(1, 21):
            data = self.get(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')
            if 'http_status' in data:
                return data
            require(isinstance(data.get(key), list), f'Malformed GitHub {key} response.')
            result.extend(data[key])
            if len(data[key]) < 100:
                return result
        raise GateError('GitHub pagination limit reached; evidence is incomplete.')


def collect(api):
    root = '/repos/' + REPOSITORY
    branch = api.get(root + '/branches/main')
    require('http_status' not in branch, 'Cannot read main; check repository access.')
    sha = branch['commit']['sha']
    runs = api.pages(root + '/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha=' + sha, 'workflow_runs')
    evidence = {'repository': REPOSITORY, 'branch': 'main', 'commit_sha': sha, 'runs': runs,
                'protection': api.get(root + '/branches/main/protection'),
                'actions_app': api.get('/apps/github-actions'), 'jobs': [], 'artifacts': []}
    if isinstance(runs, list):
        candidates = [run for run in runs if run.get('head_sha') == sha and run.get('head_branch') == 'main'
                      and run.get('path') == WORKFLOW and run.get('event') == 'push']
        if candidates:
            run = max(candidates, key=lambda item: (item['run_number'], item['run_attempt'], item['id']))
            run_id, attempt = int(run['id']), int(run['run_attempt'])
            evidence.update(run_id=run_id, run_attempt=attempt,
                            jobs=api.pages(root + f'/actions/runs/{run_id}/attempts/{attempt}/jobs', 'jobs'),
                            artifacts=api.pages(root + f'/actions/runs/{run_id}/artifacts', 'artifacts'))
    latest = api.get(root + '/branches/main')
    require('http_status' not in latest, 'Cannot re-read main; verification is incomplete.')
    require(latest.get('commit', {}).get('sha') == sha, 'main moved during collection; collect again.')
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--fetch', action='store_true', help='GET live API evidence using GH_TOKEN or GITHUB_TOKEN')
    source.add_argument('--evidence', type=Path, help='Validate an existing JSON snapshot (not live enforcement proof)')
    args = parser.parse_args()
    try:
        if args.fetch:
            token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
            require(token, 'Set a repository-scoped token through your local environment; never paste it in chat.')
            evidence = collect(GitHub(token))
        else:
            evidence = json.loads(args.evidence.read_text())
        result = verify(evidence)
        result['evidenceSource'] = 'live-api' if args.fetch else 'offline-snapshot'
        print(json.dumps(result, indent=2))
        return 0
    except (ValueError, KeyError, TypeError, AttributeError, OSError, urllib.error.URLError):
        # Specific validation errors contain only fixed messages; do not expose arbitrary API/file content.
        error = sys.exc_info()[1]
        message = str(error) if isinstance(error, GateError) else 'Malformed or inaccessible evidence; verification is incomplete.'
        print('Merge gate NOT verified: ' + message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
