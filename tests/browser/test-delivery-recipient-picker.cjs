// Built UI fixture: responsive recipient choice and preserved drafts, with no writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { engine, browserType } = require('./browser-engine.cjs');
const dist = path.resolve(__dirname, '../../frontend/dist');
const artifacts = path.join(process.env.SESSION_BROWSER_ARTIFACTS_DIR || path.resolve(__dirname, '../../.ci-artifacts/browser'), engine, 'delivery-recipient-picker');
fs.mkdirSync(artifacts, { recursive: true });
const rows = [
  { recipientId: 1, firstName: 'Alex', lastName: 'Morgan', address: 'John Rylands Library, 150 Deansgate, Manchester, M3 3EH, UK' },
  { recipientId: 2, firstName: 'Jamie', lastName: 'Taylor', address: 'Science and Industry Museum, Liverpool Road, Manchester, M3 4FP, UK' },
  { recipientId: 3, firstName: '<img src=x onerror=alert(1)>', lastName: 'LongRecipient'.repeat(10), address: 'LongUnbrokenAddress'.repeat(15) },
].map(row => ({ ...row, contactNumber: null, priorityLevel: 'MEDIUM' }));
const second = { ...rows[1], recipientId: 21, firstName: 'Sam', lastName: 'Patel' };
let mode = 'normal', writes = 0; const queries = [];
const server = http.createServer((request, response) => {
  const url = new URL(request.url, 'http://localhost'); const pathname = url.pathname;
  response.setHeader('Cache-Control', 'no-store');
  if (!['GET', 'HEAD'].includes(request.method)) { writes++; response.writeHead(405).end(); return; }
  const json = data => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(data)); };
  if (pathname === '/api/auth/me') return json({ id: 1, username: 'fixture.admin', role: 'ADMIN', operatorId: null, enabled: true, passwordChangeRequired: false });
  if (pathname === '/api/admin/deliveries') return json({ deliveries: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  if (pathname === '/api/admin/recipients') {
    const page = Number(url.searchParams.get('page')); queries.push({ page, search: url.searchParams.get('search') });
    if (mode === 'error') { response.writeHead(500).end(); return; }
    return json({ recipients: mode === 'empty' ? [] : page === 1 ? [second] : rows, page, size: 20, totalElements: mode === 'empty' ? 0 : 21, totalPages: mode === 'empty' ? 0 : 2 });
  }
  if (/^\/api\/admin\/recipients\/\d+$/.test(pathname)) {
    if (mode === 'selection-error') { response.writeHead(404).end(); return; }
    const id = Number(pathname.split('/').at(-1)); const recipient = [...rows, second].find(row => row.recipientId === id);
    response.setHeader('ETag', '"' + 'a'.repeat(64) + '"');
    return json({ ...recipient, address: `Verified: ${recipient.address}` });
  }
  if (pathname.startsWith('/api/')) { response.writeHead(404).end(); return; }
  const file = pathname.startsWith('/app/assets/') ? path.resolve(dist, pathname.slice('/app/'.length)) : path.join(dist, 'index.html');
  if (!file.startsWith(dist + path.sep) || !fs.existsSync(file)) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', ({ '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream');
  response.end(fs.readFileSync(file));
});
const report = { complete: false, engine, cases: [], keyboardCases: [] };
const save = () => fs.writeFileSync(path.join(artifacts, 'results.json'), JSON.stringify(report, null, 2)); save();
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser, page;
  try {
    browser = await browserType.launch({ headless: true, executablePath: process.env.SESSION_BROWSER_EXECUTABLE }); report.browserVersion = browser.version(); page = await browser.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    const go = () => page.goto(`http://127.0.0.1:${server.address().port}/app/admin/deliveries`);
    await go(); const table = page.getByRole('table', { name: 'Recipient search results', exact: true }); await table.waitFor();
    assert.equal(await table.getByRole('columnheader').count(), 3);
    assert.equal(await table.getByRole('rowheader').count(), 3);
    assert.equal(await table.locator('img').count(), 0);
    for (const font of ['configured', 'Arial', 'DejaVu Sans']) {
      await page.evaluate(font => { if (font === 'configured') document.documentElement.style.removeProperty('--font-body'); else document.documentElement.style.setProperty('--font-body', `"${font}", sans-serif`); }, font);
      for (const width of [320, 375, 768, 1100, 1440]) {
        let normalSize;
        for (const scale of [1, 2]) {
          await page.setViewportSize({ width, height: 1000 }); await page.evaluate(scale => document.documentElement.style.fontSize = `${scale * 100}%`, scale);
          await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
          const measured = await table.evaluate(table => {
            const bounds = element => { const rect = element.getBoundingClientRect(); return { left: rect.left, right: rect.right, height: rect.height }; };
            const text = element => { const range = document.createRange(); range.selectNodeContents(element); return bounds(range); };
            return { pageWidth: document.documentElement.scrollWidth, viewport: innerWidth, rootFontSize: parseFloat(getComputedStyle(document.documentElement).fontSize),
              clientWidth: table.clientWidth, scrollWidth: table.scrollWidth,
              fields: [...table.querySelectorAll('tbody strong, tbody small, .delivery-recipient-address')].map(element => ({ box: bounds(element.closest('th,td')), text: text(element) })),
              actions: [...table.querySelectorAll('button')].map(bounds), firstRowHeight: table.querySelector('tbody tr').getBoundingClientRect().height };
          });
          const item = { font, width, scale, ...measured, passed: false }; report.cases.push(item); save();
          if (scale === 1) normalSize = measured.rootFontSize;
          assert.ok(measured.rootFontSize >= normalSize * scale * .99, 'Text enlargement retained');
          assert.ok(measured.pageWidth <= width && measured.scrollWidth <= measured.clientWidth + 1, `Picker overflow: ${font}, ${width}px, ${scale * 100}%`);
          for (const field of measured.fields) assert.ok(field.text.left >= field.box.left - 1 && field.text.right <= field.box.right + 1, 'Recipient text must stay inside its cell');
          for (const action of measured.actions) assert.ok(action.left >= 0 && action.right <= width && action.height >= 44, 'Select action must fit and retain touch height');
          if (width === 1440 && scale === 1) assert.ok(measured.firstRowHeight <= 110, 'Ordinary desktop rows should stay compact');
          assert.equal(await table.getByRole('button').count(), 3);
          item.passed = true; save();
          if (font === 'configured' && scale === 1 && [375, 1440].includes(width)) {
            await page.locator('.delivery-picker').scrollIntoViewIfNeeded(); await page.screenshot({ path: path.join(artifacts, `picker-${width}.png`) });
          }
        }
      }
    }
    for (const width of [375, 1440]) {
      for (const scale of [1, 2]) {
        mode = 'normal'; await page.setViewportSize({ width, height: 1000 }); await go(); await page.evaluate(scale => document.documentElement.style.fontSize = `${scale * 100}%`, scale); await table.waitFor();
        await page.getByLabel('Scheduled date', { exact: true }).fill('2026-01-02'); await page.getByLabel('Special instructions (optional)', { exact: true }).fill('Keep this draft');
        const choose = table.getByRole('button', { name: 'Select Alex Morgan, recipient 1', exact: true }); await choose.focus(); await page.keyboard.press('Enter');
        const summary = page.getByRole('group', { name: 'Selected delivery recipient', exact: true }); await summary.waitFor();
        assert.ok(await page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize)) >= 16 * scale, 'Keyboard workflow retains requested text size');
        assert.match(await summary.innerText(), /Verified:/);
        assert.equal(await table.isVisible(), false); assert.equal(await page.getByRole('search', { name: 'Find a delivery recipient' }).isVisible(), false);
        await page.waitForFunction(() => document.activeElement.id === 'delivery-create');
        await summary.screenshot({ path: path.join(artifacts, `selected-${width}-${scale}.png`) });
        assert.ok(await summary.evaluate(element => element.scrollWidth <= element.clientWidth + 1));
        const change = page.getByRole('button', { name: 'Change recipient', exact: true }); await change.focus(); await page.keyboard.press('Enter');
        await page.waitForFunction(() => document.activeElement.id === 'delivery-recipient-search');
        assert.equal(await page.getByLabel('Scheduled date', { exact: true }).inputValue(), '2026-01-02');
        assert.equal(await page.getByLabel('Special instructions (optional)', { exact: true }).inputValue(), 'Keep this draft');
        assert.equal(await page.getByRole('button', { name: 'Create delivery', exact: true }).isDisabled(), true);
        await page.getByLabel('Search recipient name or address', { exact: true }).fill('Manchester'); await page.getByRole('button', { name: 'Search recipients', exact: true }).click(); await table.waitFor();
        await page.getByRole('button', { name: 'Next recipients', exact: true }).click(); const next = table.getByRole('button', { name: 'Select Sam Patel, recipient 21', exact: true }); await next.waitFor();
        await next.focus(); await page.keyboard.press('Enter'); await summary.waitFor();
        assert.match(await summary.innerText(), /Sam Patel/);
        await page.getByRole('button', { name: 'Change recipient', exact: true }).click(); await next.waitFor();
        assert.equal(await page.getByLabel('Search recipient name or address', { exact: true }).inputValue(), 'Manchester');
        assert.ok(queries.some(query => query.page === 1 && query.search === 'Manchester'));
        report.keyboardCases.push({ width, scale, passed: true }); save();
      }
    }
    mode = 'empty'; await go(); await page.getByRole('heading', { name: 'Start with a recipient', exact: true }).waitFor();
    mode = 'error'; await page.getByRole('button', { name: 'Reload recipients', exact: true }).click(); await page.getByRole('alert').waitFor();
    mode = 'normal'; await page.getByRole('button', { name: 'Reload recipients', exact: true }).click(); await table.waitFor();
    mode = 'selection-error'; await table.getByRole('button', { name: 'Select Alex Morgan, recipient 1', exact: true }).click(); await page.getByRole('alert').waitFor();
    assert.equal(await page.getByRole('button', { name: 'Create delivery', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('group', { name: 'Selected delivery recipient' }).count(), 0);
    assert.equal(writes, 0); assert.deepEqual(errors, []); report.complete = true; report.writes = writes; save();
    console.log(`Recipient picker: ${report.cases.length} responsive layouts and four keyboard/draft cases passed.`);
  } catch (error) { if (page) await page.screenshot({ path: path.join(artifacts, 'failure.png') }).catch(() => {}); throw error; }
  finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
