// Required cross-engine fixtures against the built frontend; no production services.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
// Opt-in Linux runtime for hosts where a bundled engine cannot launch (not a skip).
if (process.env.ACCEPTANCE_USE_CONTAINER === '1') {
  const root = path.resolve(__dirname, '../..');
  const version = require('./package-lock.json').packages['node_modules/playwright'].version;
  const directory = path.resolve(process.env.SESSION_BROWSER_ARTIFACTS_DIR || path.join(root, '.ci-artifacts/browser'));
  assert.ok(directory.startsWith(root + path.sep), 'Container artifacts must stay inside the repository');
  const result = spawnSync('docker', ['run', '--rm', '--network', 'none', '--shm-size=1g',
    '-v', `${root}:${root}`, '-w', root, '-e', `SESSION_BROWSER_ARTIFACTS_DIR=${directory}`,
    `mcr.microsoft.com/playwright:v${version}-noble`, 'node', __filename], { stdio: 'inherit', timeout: 600000 });
  assert.equal(result.status, 0, `Container acceptance failed: ${result.error?.message || result.signal || 'see diagnostics'}`);
  process.exit(0);
}
const artifacts = process.env.SESSION_BROWSER_ARTIFACTS_DIR || path.resolve(__dirname, '../../.ci-artifacts/browser');
const suites = [['test-workspace-reflow.cjs', 'reflow', 72], ['test-render-recovery.cjs', 'render-recovery', 24],
  ['test-admin-route-directory.cjs', 'admin-route-directory', 30], ['test-generation-mobile-review.cjs', 'generation-mobile-review', 30],
  ['test-delivery-recipient-picker.cjs', 'delivery-recipient-picker', 30]];
fs.mkdirSync(artifacts, { recursive: true });
const report = { complete: false, platform: process.platform, nodeVersion: process.version, playwrightVersion: require('./package-lock.json').packages['node_modules/playwright'].version, results: [] };
const save = () => fs.writeFileSync(path.join(artifacts, 'cross-browser-results.json'), JSON.stringify(report, null, 2));
save();
for (const engine of ['chromium', 'firefox', 'webkit']) {
  for (const [script, directory, count] of suites) {
    const reportPath = path.join(artifacts, engine, directory, 'results.json');
    fs.rmSync(reportPath, { force: true });
    const result = spawnSync(process.execPath, [path.join(__dirname, script)], {
      env: { ...process.env, ACCEPTANCE_BROWSER: engine }, stdio: 'inherit', timeout: 180000,
    });
    assert.equal(result.status, 0, `${engine}/${directory} failed: ${result.error?.message || result.signal || 'see diagnostics'}`);
    const evidence = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
    assert.ok(evidence.complete && evidence.engine === engine);
    assert.equal(evidence.cases.length, count);
    assert.ok(evidence.cases.every(item => item.passed));
    if (directory === 'admin-route-directory') assert.equal(evidence.navigation, true);
    if (['generation-mobile-review', 'delivery-recipient-picker'].includes(directory)) {
      assert.equal(evidence.keyboardCases.length, 4);
      assert.ok(evidence.keyboardCases.every(item => item.passed));
    }
    report.results.push({ engine, suite: directory, cases: count, passed: true }); save();
  }
}
report.complete = true; save(); console.log(`Cross-browser acceptance: ${report.results.length} required suites and ${report.results.reduce((sum, item) => sum + item.cases, 0)} layout cases passed.`);
