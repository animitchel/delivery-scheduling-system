// Exercise the real built frontend with disposable session fixtures; no live backend is used.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const dist = path.resolve(__dirname, '../../frontend/dist');
const artifacts = path.join(process.env.SESSION_BROWSER_ARTIFACTS_DIR || path.resolve(__dirname, '../../.ci-artifacts/browser'), 'reflow');
fs.mkdirSync(artifacts, { recursive: true });
assert.ok(fs.existsSync(path.join(dist, 'index.html')), 'Build the frontend before checking reflow');
let account;
const server = http.createServer((request, response) => {
  const pathname = new URL(request.url, 'http://localhost').pathname;
  if (pathname === '/api/auth/me') {
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(account)); return;
  }
  if (pathname.startsWith('/api/')) { response.writeHead(404).end(); return; }
  const file = pathname.startsWith('/app/assets/') ? path.resolve(dist, pathname.slice('/app/'.length)) : path.join(dist, 'index.html');
  if (!file.startsWith(dist + path.sep) || !fs.existsSync(file)) { response.writeHead(404).end(); return; }
  const types = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };
  response.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream'); response.end(fs.readFileSync(file));
});
const report = { complete: false, platform: process.platform, cases: [] };
const save = () => fs.writeFileSync(path.join(artifacts, 'results.json'), JSON.stringify(report, null, 2));
save();
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser, page;
  try {
    browser = await chromium.launch({ headless: true, executablePath: process.env.SESSION_BROWSER_EXECUTABLE });
    for (const profile of ['administrator', 'long-administrator', 'long-driver']) {
      const driver = profile === 'long-driver';
      account = { id: 1, username: profile === 'administrator' ? 'smoke.admin' : 'test.account.'.padEnd(64, 'x'), role: driver ? 'DRIVER' : 'ADMIN', operatorId: driver ? 1 : null, enabled: true, passwordChangeRequired: false };
      const context = await browser.newContext(); page = await context.newPage();
      await page.goto(`http://127.0.0.1:${server.address().port}/app/${driver ? 'driver' : 'admin'}`);
      await page.getByRole('heading', { name: driver ? 'Your next stop starts here.' : 'Keep deliveries moving.', exact: true }).waitFor();
      for (const font of ['configured', 'Arial', 'DejaVu Sans']) {
        await page.evaluate(font => {
          if (font === 'configured') document.documentElement.style.removeProperty('--font-body');
          else document.documentElement.style.setProperty('--font-body', `"${font}", sans-serif`);
        }, font);
        for (const width of [320, 375, 768, 1440]) {
          await page.setViewportSize({ width, height: 900 });
          let normalRootSize;
          for (const scale of [1, 2]) {
            await page.evaluate(scale => document.documentElement.style.fontSize = `${scale * 100}%`, scale);
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            const measured = await page.evaluate(() => {
              const bounds = element => { const rect = element.getBoundingClientRect(); return { left: rect.left, right: rect.right }; };
              const textBounds = element => { const range = document.createRange(); range.selectNodeContents(element); return bounds(range); };
              const heading = document.querySelector('.page-intro h1'); const role = document.querySelector('.role-label');
              return { contentWidth: document.documentElement.scrollWidth, viewport: innerWidth, rootFontSize: getComputedStyle(document.documentElement).fontSize,
                headingText: textBounds(heading), roleBox: bounds(role), roleText: textBounds(role), signOut: bounds(document.querySelector('.session-actions button')) };
            });
            if (scale === 1) normalRootSize = parseFloat(measured.rootFontSize);
            const label = `${profile}, ${font}, ${width}px, ${scale * 100}% text`;
            const result = { profile, font, width, scale, ...measured, passed: false };
            report.cases.push(result); save();
            assert.ok(parseFloat(measured.rootFontSize) >= normalRootSize * scale * .99, `Text sizing was suppressed: ${label}`);
            assert.ok(measured.contentWidth <= measured.viewport, `Horizontal overflow: ${label}: ${measured.contentWidth}px / ${measured.viewport}px`);
            assert.ok(measured.headingText.left >= -1 && measured.headingText.right <= width + 1, `Heading text leaves the viewport: ${label}`);
            assert.ok(measured.roleText.left >= measured.roleBox.left - 1 && measured.roleText.right <= measured.roleBox.right + 1, `Role text leaves its container: ${label}`);
            assert.ok(measured.signOut.left >= 0 && measured.signOut.right <= width, `Sign out leaves the viewport: ${label}`);
            result.passed = true; save();
            if (profile === 'administrator' && width === 320 && scale === 2 && font !== 'Arial') {
              await page.screenshot({ path: path.join(artifacts, `administrator-320-${font.replaceAll(' ', '-')}.png`) });
            }
          }
        }
      }
      await context.close(); page = null;
    }
    report.complete = true; save(); console.log(`Workspace reflow: ${report.cases.length} cases passed (${report.platform}).`);
  } catch (error) {
    if (page) await page.screenshot({ path: path.join(artifacts, 'failure.png') }).catch(() => {});
    throw error;
  } finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
