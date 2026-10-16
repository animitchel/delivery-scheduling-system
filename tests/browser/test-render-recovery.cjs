// Fault injection is served only by this disposable test server, never shipped in the app.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { engine, browserType } = require('./browser-engine.cjs');
const dist = path.resolve(__dirname, '../../frontend/dist');
const artifacts = path.join(process.env.SESSION_BROWSER_ARTIFACTS_DIR || path.resolve(__dirname, '../../.ci-artifacts/browser'), engine, 'render-recovery');
fs.mkdirSync(artifacts, { recursive: true });
assert.ok(fs.existsSync(path.join(dist, 'index.html')), 'Build the frontend before checking recovery');
const privateMessage = 'Private fixture account and credential details';
// Throw from Overview's real card rendering without adding a production crash switch.
const fault = `{const originalMap=Array.prototype.map;Array.prototype.map=function(callback,...args){if(this[0]?.title==='Manage warehouses'&&this[0]?.href==='/app/admin/warehouses')throw new Error(${JSON.stringify(privateMessage)});return Reflect.apply(originalMap,this,[callback,...args]);};}\n`;
let injectFault = true, writes = 0, documents = 0;
const server = http.createServer((request, response) => {
  const pathname = new URL(request.url, 'http://localhost').pathname;
  response.setHeader('Cache-Control', 'no-store');
  if (!['GET', 'HEAD'].includes(request.method)) { writes++; response.writeHead(405).end(); return; }
  if (pathname === '/api/auth/me') {
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ id: 1, username: 'fixture.admin', role: 'ADMIN', operatorId: null, enabled: true, passwordChangeRequired: false })); return;
  }
  if (pathname.startsWith('/api/')) { response.writeHead(404).end(); return; }
  const file = pathname.startsWith('/app/assets/') ? path.resolve(dist, pathname.slice('/app/'.length)) : path.join(dist, 'index.html');
  if (!file.startsWith(dist + path.sep) || !fs.existsSync(file)) { response.writeHead(404).end(); return; }
  const extension = path.extname(file);
  response.setHeader('Content-Type', ({ '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' })[extension] || 'application/octet-stream');
  if (pathname === '/app/admin') documents++;
  const contents = fs.readFileSync(file);
  response.end(extension === '.js' && injectFault ? fault + contents.toString() : contents);
});
const report = { complete: false, engine, cases: [] };
const save = () => fs.writeFileSync(path.join(artifacts, 'results.json'), JSON.stringify(report, null, 2));
save();
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser, page;
  try {
    browser = await browserType.launch({ headless: true, executablePath: process.env.SESSION_BROWSER_EXECUTABLE }); report.browserVersion = browser.version();
    page = await browser.newPage();
    const diagnostics = [], uncaught = [];
    page.on('console', message => { if (message.type() === 'error') diagnostics.push(message.text()); });
    page.on('pageerror', error => uncaught.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/app/admin`);
    await page.getByRole('main', { name: 'Something went wrong.', exact: true }).waitFor();
    await page.waitForFunction(() => document.title === 'Application recovery | Dispatch' && document.activeElement.id === 'recovery-title');
    assert.ok(!(await page.locator('body').innerText()).includes(privateMessage), 'Recovery must not expose raw error details');
    assert.ok(!(await page.locator('body').innerText()).includes('fixture.admin'), 'The failed workspace identity must be unmounted');
    assert.ok((await page.getByRole('alert').innerText()).includes('Reload the application'));
    await page.getByText('Unsaved changes may be lost.', { exact: false }).waitFor();
    assert.equal(documents, 1, 'Recovery must not reload automatically'); assert.equal(writes, 0);
    for (const font of ['configured', 'Arial', 'DejaVu Sans']) {
      await page.evaluate(font => {
        if (font === 'configured') document.documentElement.style.removeProperty('--font-body');
        else document.documentElement.style.setProperty('--font-body', `"${font}", sans-serif`);
      }, font);
      let normalRootSize;
      for (const width of [320, 375, 768, 1440]) for (const scale of [1, 2]) {
        await page.setViewportSize({ width, height: 900 });
        await page.evaluate(scale => document.documentElement.style.fontSize = `${scale * 100}%`, scale);
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const bounds = await page.evaluate(() => {
          const heading = document.createRange(); heading.selectNodeContents(document.querySelector('#recovery-title'));
          const text = heading.getBoundingClientRect(); const action = document.querySelector('.recovery-panel button').getBoundingClientRect();
          return { content: document.documentElement.scrollWidth, viewport: innerWidth, headingLeft: text.left, headingRight: text.right,
            buttonLeft: action.left, buttonRight: action.right, rootFontSize: parseFloat(getComputedStyle(document.documentElement).fontSize) };
        });
        const result = { font, width, scale, ...bounds, passed: false }; report.cases.push(result); save();
        if (scale === 1) normalRootSize = bounds.rootFontSize;
        assert.ok(bounds.rootFontSize >= normalRootSize * scale * .99, 'Text sizing must not be suppressed');
        assert.ok(bounds.content <= width && bounds.headingLeft >= -1 && bounds.headingRight <= width + 1, 'Recovery text must fit the viewport');
        assert.ok(bounds.buttonLeft >= 0 && bounds.buttonRight <= width, 'Reload action must fit the viewport');
        result.passed = true; save();
        if (font === 'configured' && scale === 1 && [320, 1440].includes(width)) await page.screenshot({ path: path.join(artifacts, `recovery-${width}.png`), fullPage: true });
      }
    }
    assert.equal(documents, 1, 'Resizing must not retry the application'); assert.equal(writes, 0);
    await page.keyboard.press('Tab');
    assert.equal(await page.getByRole('button', { name: 'Reload application', exact: true }).evaluate(node => node === document.activeElement), true);
    injectFault = false;
    await page.keyboard.press('Enter');
    await page.getByRole('heading', { name: 'Keep deliveries moving.', exact: true }).waitFor();
    await page.waitForFunction(() => document.title === 'Operations overview | Dispatch');
    assert.equal(documents, 2, 'Explicit recovery must reload the document once'); assert.equal(writes, 0);
    assert.deepEqual(uncaught, []); assert.ok(diagnostics.length > 0);
    assert.ok(diagnostics.every(message => message === 'Application rendering failed.'), 'Caught render diagnostics must stay fixed and nonsensitive');
    report.complete = true; report.writes = writes; report.documents = documents; report.caughtDiagnostics = diagnostics.length; save();
    console.log(`Render recovery: ${report.cases.length} layout cases, private fallback and explicit keyboard reload passed.`);
  } catch (error) {
    if (page) await page.screenshot({ path: path.join(artifacts, 'failure.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
