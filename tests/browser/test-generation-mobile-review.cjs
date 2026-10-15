// Exercise the built route directory with disposable data and no live backend.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const dist = path.resolve(__dirname, '../../frontend/dist');
const artifacts = path.join(process.env.SESSION_BROWSER_ARTIFACTS_DIR || path.resolve(__dirname, '../../.ci-artifacts/browser'), 'generation-mobile-review');
fs.mkdirSync(artifacts, { recursive: true });
const longName = '<img src=x onerror=alert(1)> ' + 'LongOperator'.repeat(12);
const rows = ['IN_PROGRESS', 'PLANNED', 'COMPLETED'].map((routeStatus, index) => ({ routeId: index + 1, operatorId: index + 1,
  operatorName: index === 0 ? longName : `Operator ${index + 1}`, scheduledDate: '2026-10-15', routeStatus, stopCount: 3, finishedCount: index === 2 ? 3 : 0 }));
const second = { ...rows[1], routeId: 21, operatorId: 21, operatorName: 'Second page operator' };
let mode = 'normal', writes = 0; const pages = [], dates = [];
const server = http.createServer((request, response) => {
  const url = new URL(request.url, 'http://localhost'); const pathname = url.pathname;
  response.setHeader('Cache-Control', 'no-store');
  const json = data => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(data)); };
  if (request.method === 'POST' && pathname === '/api/admin/routes/generate') {
    writes++;
    if (writes === 2) { response.writeHead(503).end(); return; }
    response.statusCode = 201; return json({ scheduledDate: '2026-10-15', processingTimeMs: 10, routes: [rows[0]] });
  }
  if (!['GET', 'HEAD'].includes(request.method)) { throw new Error('Unexpected fixture write'); }
  if (pathname === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'disposable-browser-fixture' });
  if (pathname === '/api/auth/me') return json({ id: 1, username: 'fixture.admin', role: 'ADMIN', operatorId: null, enabled: true, passwordChangeRequired: false });
  if (pathname === '/api/admin/routes') {
    const page = Number(url.searchParams.get('page')); pages.push(page); dates.push(url.searchParams.get('scheduledDate'));
    if (mode === 'error') { response.writeHead(500).end(); return; }
    return json({ routes: mode === 'empty' ? [] : page === 1 ? [second] : rows, page, size: 20, totalElements: mode === 'empty' ? 0 : 21, totalPages: mode === 'empty' ? 0 : 2 });
  }
  if (/^\/api\/admin\/routes\/\d+$/.test(pathname)) {
    const id = Number(pathname.split('/').at(-1)); const item = [...rows, second].find(item => item.routeId === id) || { ...rows[2], routeId: id };
    return json({ ...item, deliveries: Array.from({ length: 3 }, (_, index) => ({ deliveryId: id * 10 + index, recipientId: index + 1,
      clientName: `Fixture recipient ${index + 1}`, status: item.routeStatus === 'COMPLETED' ? 'COMPLETED' : item.routeStatus === 'PLANNED' ? 'PENDING' : 'IN_PROGRESS',
      fromPrevDistanceKm: null, fromPrevDurationText: null, fromPrevDurationSeconds: null })) });
  }
  if (pathname.startsWith('/api/')) { response.writeHead(404).end(); return; }
  const file = pathname.startsWith('/app/assets/') ? path.resolve(dist, pathname.slice('/app/'.length)) : path.join(dist, 'index.html');
  if (!file.startsWith(dist + path.sep) || !fs.existsSync(file)) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', ({ '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream');
  response.end(fs.readFileSync(file));
});
const report = { complete: false, cases: [], keyboardCases: [] };
const save = () => fs.writeFileSync(path.join(artifacts, 'results.json'), JSON.stringify(report, null, 2)); save();
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser, page;
  try {
    browser = await chromium.launch({ headless: true, executablePath: process.env.SESSION_BROWSER_EXECUTABLE }); page = await browser.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/app/admin/routes/generate`);
    const table = page.getByRole('table', { name: 'Routes for generation review', exact: true }); await table.waitFor();
    assert.equal(await table.getByRole('rowheader').count(), 3); assert.equal(await table.getByRole('columnheader').count(), 6);
    assert.equal(await table.getByRole('button').count(), 3); assert.equal(await table.locator('img').count(), 0);
    for (const label of ['Planned', 'In progress', 'Completed']) assert.equal(await table.getByRole('cell', { name: label, exact: true }).count(), 1);
    for (const font of ['configured', 'Arial', 'DejaVu Sans']) {
      await page.evaluate(font => {
        if (font === 'configured') document.documentElement.style.removeProperty('--font-body');
        else document.documentElement.style.setProperty('--font-body', `"${font}", sans-serif`);
      }, font);
      for (const width of [320, 375, 768, 1100, 1440]) {
        let normalSize;
        for (const scale of [1, 2]) {
          await page.setViewportSize({ width, height: 1000 }); await page.evaluate(scale => document.documentElement.style.fontSize = `${scale * 100}%`, scale);
          await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
          const measured = await table.evaluate(table => {
            const bounds = element => { const box = element.getBoundingClientRect(); return { left: box.left, right: box.right }; };
            const textBounds = element => { const range = document.createRange(); range.selectNodeContents(element); return bounds(range); };
            return { pageWidth: document.documentElement.scrollWidth, viewport: innerWidth, rootFontSize: parseFloat(getComputedStyle(document.documentElement).fontSize),
              clientWidth: table.clientWidth, scrollWidth: table.scrollWidth,
              fields: [...table.querySelectorAll('tbody th > strong, tbody td > span:last-child, tbody button, tbody a')].map(element => ({ box: bounds(element.closest('th,td')), text: textBounds(element) })),
              actions: [...table.querySelectorAll('button,a')].map(bounds) };
          });
          const item = { font, width, scale, ...measured, passed: false }; report.cases.push(item); save();
          if (scale === 1) normalSize = measured.rootFontSize;
          assert.ok(measured.rootFontSize >= normalSize * scale * .99, 'Text enlargement must be retained');
          assert.ok(measured.pageWidth <= width && measured.scrollWidth <= measured.clientWidth + 1, `Directory overflow: ${font}, ${width}px, ${scale * 100}%`);
          for (const field of measured.fields) assert.ok(field.text.left >= field.box.left - 1 && field.text.right <= field.box.right + 1, `Field text leaves its cell: ${font}, ${width}px, ${scale * 100}%`);
          for (const action of measured.actions) assert.ok(action.left >= 0 && action.right <= width, 'Route action must remain in the viewport');
          assert.equal(await table.getByRole('button').count(), 3); assert.equal(await table.getByRole('link').count(), 3);
          item.passed = true; save();
          if (font === 'configured' && scale === 1 && [375, 1440].includes(width)) {
            await page.locator('.generation-directory-results').evaluate(element => element.scrollIntoView({ block: 'start' }));
            await page.screenshot({ path: path.join(artifacts, `directory-${width}.png`) });
          }
        }
      }
    }
    await page.setViewportSize({ width: 375, height: 1000 }); await page.evaluate(() => document.documentElement.style.removeProperty('font-size'));
    const target = table.getByRole('button', { name: 'Inspect route 2', exact: true });
    await target.focus(); await page.keyboard.press('Enter');
    await page.getByRole('heading', { name: 'Inspect route #2', exact: true }).waitFor();
    await page.waitForFunction(() => document.activeElement.id === 'generation-stops');
    await page.getByRole('button', { name: 'Close route inspection', exact: true }).click();
    await page.waitForFunction(() => document.activeElement.id === 'generation-directory');
    await page.getByLabel('Routes generated for date', { exact: true }).fill('2026-10-15');
    await page.getByRole('button', { name: 'Apply route date', exact: true }).click();
    await table.getByRole('button', { name: 'Inspect route 1', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Next generation routes', exact: true }).click();
    await table.getByRole('button', { name: 'Inspect route 21', exact: true }).waitFor();
    await page.waitForFunction(() => document.activeElement.id === 'generation-directory');
    assert.ok(pages.includes(1)); assert.equal(await table.getByRole('link').count(), 1);
    mode = 'empty'; await page.getByRole('button', { name: 'Reload route directory', exact: true }).click();
    await page.getByText('No routes match this date filter.', { exact: true }).waitFor();
    assert.equal(await page.getByRole('table').count(), 0);
    mode = 'error'; await page.getByRole('button', { name: 'Reload route directory', exact: true }).click(); await page.getByRole('alert').waitFor();
    mode = 'normal'; await page.getByRole('button', { name: 'Reload route directory', exact: true }).click(); await table.waitFor();
    await page.getByLabel('Planning cutoff date', { exact: true }).fill('2026-10-15');
    const review = page.getByRole('button', { name: 'Review assignment', exact: true });
    const dialog = page.getByRole('dialog', { name: 'Generate and assign routes?', exact: true });
    const keep = dialog.getByRole('button', { name: 'Keep reviewing', exact: true });
    const confirm = dialog.getByRole('button', { name: 'Confirm generation and assignment', exact: true });
    const focused = locator => locator.evaluate(node => node === document.activeElement);
    for (const width of [375, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      for (const scale of [1, 2]) {
        await page.evaluate(scale => document.documentElement.style.fontSize = `${scale * 100}%`, scale);
        await review.focus(); await page.keyboard.press('Enter'); await dialog.waitFor();
        assert.ok(await focused(keep), 'Opening must focus the noncommitting action');
        assert.ok(await dialog.evaluate(node => node.getBoundingClientRect().width <= innerWidth));
        await page.keyboard.press('Enter'); await dialog.waitFor({ state: 'hidden' });
        assert.ok(await focused(review)); assert.equal(writes, 0, 'Initial Enter must not generate');
        await page.keyboard.press('Enter'); await dialog.waitFor(); assert.ok(await focused(keep));
        await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' });
        assert.ok(await focused(review)); assert.equal(writes, 0, 'Escape must not generate');
        await page.keyboard.press('Enter'); await dialog.waitFor();
        await page.keyboard.press('Shift+Tab'); assert.ok(await focused(confirm), 'Confirmation remains keyboard reachable');
        await page.keyboard.press('Tab'); assert.ok(await focused(keep));
        await page.keyboard.press('Enter'); await dialog.waitFor({ state: 'hidden' });
        assert.equal(writes, 0);
        report.keyboardCases.push({ width, scale, passed: true }); save();
      }
    }
    await page.setViewportSize({ width: 375, height: 1000 });
    await page.keyboard.press('Enter'); await dialog.waitFor(); assert.ok(await focused(keep));
    await page.keyboard.press('Shift+Tab'); assert.ok(await focused(confirm));
    await page.keyboard.press('Enter');
    await page.getByRole('link', { name: 'Open generated route #1', exact: true }).waitFor();
    assert.equal(writes, 1);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Acknowledgement cards must fit enlarged mobile text');
    await page.getByRole('button', { name: 'Review assignment', exact: true }).click();
    await dialog.getByRole('button', { name: 'Confirm generation and assignment', exact: true }).click();
    await page.getByRole('heading', { name: 'Review before generating again', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Review assignment', exact: true }).isDisabled(), true);
    const allow = page.getByRole('button', { name: 'I reviewed results; allow another request', exact: true });
    assert.equal(await allow.isDisabled(), true);
    await page.getByRole('button', { name: 'Review existing routes for this date', exact: true }).click();
    await table.getByRole('cell', { name: 'Completed', exact: true }).waitFor();
    await page.waitForFunction(() => ![...document.querySelectorAll('button')].find(node => node.textContent === 'I reviewed results; allow another request').disabled);
    assert.equal(dates.at(-1), '2026-10-15'); assert.equal(writes, 2);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Recovery must fit enlarged mobile text');
    await page.screenshot({ path: path.join(artifacts, 'recovery-375-enlarged.png') });
 assert.deepEqual(errors, []); report.complete = true; report.writes = writes; save();
    console.log(`Generation review: ${report.cases.length} layout cases, keyboard inspection, pagination, safe initial focus, Enter/Escape/Tab confirmation, acknowledgement, uncertain-write review and read recovery passed.`);
  } catch (error) { if (page) await page.screenshot({ path: path.join(artifacts, 'failure.png') }).catch(() => {}); throw error; }
  finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
