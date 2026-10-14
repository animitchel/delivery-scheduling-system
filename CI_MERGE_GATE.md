# GitHub CI and the main merge gate

## Current live status: blocked

On 14 October 2026, repository-scoped GitHub API inspection confirmed:

| Item | Evidence | Status |
| --- | --- | --- |
| Repository visibility | Repository API reports `private: true` | Keep private |
| Current main | `c2616ff9c8b0f3ae0a35acdf2e713fc1fa1c83c4` (merged PR #35) | Verified at inspection |
| Main protection | Branch API reports `protected: false` | Not enforced |
| Branch rules/rulesets | HTTP 403: “Upgrade to GitHub Pro or make this repository public to enable this feature.” | Plan blocks enforcement |
| Classic protection API | HTTP 403: “Resource not accessible by personal access token” | Credential cannot inspect/manage protection |
| Actions runs API | HTTP 403: “Resource not accessible by personal access token” | Hosted results unverified |

The owner chose to keep the repository private and document enforcement as blocked. No plan purchase, visibility change, branch-policy mutation, workflow dispatch or pull request was performed. Local passing tests do not establish GitHub-hosted success or an enforced merge gate.

GitHub documents [private-repository protection eligibility](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches) and the [administration permission needed to read protection](https://docs.github.com/en/rest/branches/branch-protection). Repository ownership does not give a limited token every API permission.

## Reviewable policy, not an applied policy

`.github/policies/main-branch-protection.json` is a proposed classic protection request for the currently unprotected `main`. Merely committing this file does not activate GitHub protection.

It requires pull requests and **Verify application** from GitHub Actions, with an up-to-date base branch. It applies to administrators and disables force pushes/deletion. The GitHub Actions app ID **15368** was independently confirmed through the [public app API](https://api.github.com/apps/github-actions). It does not require another approving reviewer, preserving the owner's manual merge workflow.

Before applying it, inspect the then-current policy and preserve any existing stricter review requirements, restrictions or other controls. Do not blindly replace a policy that someone has added since this inspection. Apply only after the plan supports it and the real check has reported successfully. Retain the existing stable job name; duplicate check names in other workflows can undermine or confuse requirements.

## Read-only verification

```sh
python3 scripts/verify-github-merge-gate.py --fetch
```

Supply an authorized repository-scoped credential through the local `GH_TOKEN` or `GITHUB_TOKEN` environment. Do not put it in arguments, commit it or paste it in chat. The verifier does not retrieve Git credentials, change settings, trigger workflows or create PRs. It uses GET requests only, blocks redirects and does not log credentials, request headers or API error bodies. Live access needs repository metadata/contents read, Actions read and Administration read. Applying the future policy additionally needs Administration write.

The verifier requires a successful **main-push** run of `.github/workflows/ci.yml` for the current full SHA, its latest attempt, the successful verification/browser setup/report upload/JAR upload steps, and nonexpired nonempty report/application artifacts from that attempt. Older success, manual-dispatch evidence, skipped steps and access errors cannot pass. It re-reads main to reject a commit moving during collection.

It then verifies the classic protection profile above, including the check's source integration and bypass controls. This is metadata/step evidence, not a download or cryptographic attestation of the artifact contents. The application job's existing independent evidence validator checks the reports and packaged resources before upload. Inspect/download the real reports and JAR when completing hosted acceptance.

`--evidence PATH` checks an existing JSON snapshot using the same validator and labels the result `offline-snapshot`. Fixture success does not prove live GitHub enforcement. Rulesets are not automatically interpreted by this helper: if using a ruleset instead of classic protection, inspect applicable active rules and bypass actors separately; a verifier rejection is not proof that such a ruleset is absent.

## Completing the blocked work later

1. Keep the repository private and enable a GitHub plan supporting its protection features.
2. Grant the repository credential Actions read and Administration read/write access. Confirm the Actions and protection APIs are accessible.
3. Inspect the current main-push run, all required test evidence and uploaded application. Resolve actual hosted failures before requiring the check.
4. Inspect current settings; apply the reviewed protection policy without removing existing stronger requirements.
5. Re-read settings, run the live verifier and inspect a real PR's required-check display. Confirm unsuccessful checks block normal merging and administrator bypass is disabled. Do not describe a saved JSON template or fixture as this evidence.
6. Record the verified main SHA, run/attempt, artifact references and applied policy in this document/roadmap.

If a merge queue is introduced later, add and verify the separate `merge_group` trigger as [GitHub requires](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks#status-checks-with-github-actions-and-a-merge-queue). This branch does not enable a queue or change the existing CI workflow.

## Local tests

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The existing CI runner discovers these tests automatically; they require no GitHub credential. Positive/negative fixtures cover stale/failed/pending/cancelled attempts, missing/skipped steps, expired/empty/old artifacts, wrong integration/check, missing protection, bypasses, pagination, main moving, GET-only requests, redirection and CLI failure handling. They verify the tooling, not the blocked account features.

Validation on this branch: all **36 Python tests** passed (17 merge-gate tests plus 19 existing helper/evidence tests), Python compilation and Git whitespace checks passed. An actual authenticated GET-only verifier invocation returned exit status 1 for the blocked Actions API without exposing its credential. No hosted run, artifact or enforcement success is claimed. Application code and the CI workflow are unchanged; the backend/frontend suites were not repeated for this tooling/documentation change.
