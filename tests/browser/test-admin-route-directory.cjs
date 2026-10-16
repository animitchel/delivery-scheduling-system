// Exercise the built route directory with disposable data and no live backend.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { engine, browserType } = require('./browser-engine.cjs');
const dist = path.resolve(__dirname, '../../frontend/dist');
const artifacts = path.join(process.env.SESSION_BROWSER_ARTIFACTS_DIR || path.resolve(__dirname, '../../.ci-artifacts/browser'), engine, 'admin-route-directory');
fs.mkdirSync(artifacts, { recursive: true });
const longName = '<img src=x onerror=alert(1)> ' + 'LongOperator'.repeat(12);
const rows = ['IN_PROGRESS', 'PLANNED', 'COMPLETED'].map((routeStatus, index) => ({ routeId: index + 1, operatorId: index + 1,
  operatorName: index === 0 ? longName : `Operator ${index + 1}`, scheduledDate: '2026-10-15', routeStatus, stopCount: 3, finishedCount: index === 2 ? 3 : 0 }));
const second = { ...rows[1], routeId: 21, operatorId: 21, operatorName: 'Second page operator' };
let mode = 'normal', writes = 0; const pages = [];
const server = http.createServer((request, response) => {
  const url = new URL(request.url, 'http://localhost'); const pathname = url.pathname;
  response.setHeader('Cache-Control', 'no-store');
  if (!['GET', 'HEAD'].includes(request.method)) { writes++; response.writeHead(405).end(); return; }
  const json = data => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(data)); };
  if (pathname === '/api/auth/me') return json({ id: 1, username: 'fixture.admin', role: 'ADMIN', operatorId: null, enabled: true, passwordChangeRequired: false });
  if (pathname === '/api/v1/scheduling-app/routes/directory') {
    const page = Number(url.searchParams.get('page')); pages.push(page);
    if (mode === 'error') { response.writeHead(500).end(); return; }
    return json({ routes: mode === 'empty' ? [] : page === 1 ? [second] : rows, page, size: 20, totalElements: mode === 'empty' ? 0 : 21, totalPages: mode === 'empty' ? 0 : 2 });
  }
  if (/^\/api\/v1\/scheduling-app\/routes\/\d+$/.test(pathname)) {
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
const report = { complete: false, engine, cases: [] };
const save = () => fs.writeFileSync(path.join(artifacts, 'results.json'), JSON.stringify(report, null, 2)); save();
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser, page;
  try {
    browser = await browserType.launch({ headless: true, executablePath: process.env.SESSION_BROWSER_EXECUTABLE }); report.browserVersion = browser.version(); page = await browser.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/app/admin/routes`);
    const table = page.getByRole('table', { name: 'Routes and assignment progress', exact: true }); await table.waitFor();
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
              fields: [...table.querySelectorAll('tbody th > span:first-child, tbody td > span:last-child, tbody button')].map(element => ({ box: bounds(element.closest('th,td')), text: textBounds(element) })),
              actions: [...table.querySelectorAll('button')].map(bounds) };
          });
          const item = { font, width, scale, ...measured, passed: false }; report.cases.push(item); save();
          if (scale === 1) normalSize = measured.rootFontSize;
          assert.ok(measured.rootFontSize >= normalSize * scale * .99, 'Text enlargement must be retained');
          assert.ok(measured.pageWidth <= width && measured.scrollWidth <= measured.clientWidth + 1, `Directory overflow: ${font}, ${width}px, ${scale * 100}%`);
          for (const field of measured.fields) assert.ok(field.text.left >= field.box.left - 1 && field.text.right <= field.box.right + 1, `Field text leaves its cell: ${font}, ${width}px, ${scale * 100}%`);
          for (const action of measured.actions) assert.ok(action.left >= 0 && action.right <= width, 'Route action must remain in the viewport');
          assert.equal(await table.getByRole('button').count(), 3, 'Each route needs exactly one accessible action');
          item.passed = true; save();
          if (font === 'configured' && scale === 1 && [375, 1440].includes(width)) {
            await page.locator('.route-directory').evaluate(element => element.scrollIntoView({ block: 'start' }));
            await page.screenshot({ path: path.join(artifacts, `directory-${width}.png`) });
          }
        }
      }
    }
    await page.setViewportSize({ width: 375, height: 1000 }); await page.evaluate(() => document.documentElement.style.removeProperty('font-size'));
    const target = table.getByRole('button', { name: 'View route #2', exact: true }); await target.focus(); await page.keyboard.press('Enter');
    await page.getByRole('region', { name: 'Route #2 overview', exact: true }).waitFor();
    await page.waitForFunction(() => document.activeElement.getAttribute('aria-label') === 'Route #2 overview');
    assert.equal(await table.getByRole('button', { name: 'View route #2', exact: true }).getAttribute('aria-current'), 'true');
    await page.getByRole('button', { name: 'Next routes', exact: true }).click(); await table.getByRole('button', { name: 'View route #21', exact: true }).waitFor();
    await page.waitForFunction(() => document.activeElement.id === 'admin-directory-title');
    assert.ok(pages.includes(1)); assert.equal(await table.getByRole('button').count(), 1);
    await page.getByRole('button', { name: 'Previous routes', exact: true }).click(); await table.getByRole('button', { name: 'View route #1', exact: true }).waitFor();
    await page.goto(`http://127.0.0.1:${server.address().port}/app/admin/routes?routeId=91`);
    await page.getByRole('region', { name: 'Route #91 overview', exact: true }).waitFor(); await page.getByText('Viewing route #91 from a direct link; it is outside this directory page.').waitFor();
    mode = 'empty'; await page.goto(`http://127.0.0.1:${server.address().port}/app/admin/routes`); await page.getByRole('heading', { name: 'No routes found', exact: true }).waitFor();
    assert.equal(await page.getByRole('table').count(), 0);
    mode = 'error'; await page.getByRole('button', { name: 'Reload routes', exact: true }).click(); await page.getByRole('alert').waitFor();
    mode = 'normal'; await page.getByRole('button', { name: 'Reload routes', exact: true }).click(); await table.waitFor();
    // Exercise navigation with the same built app and disposable session on every engine.
    for (const width of [375, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.evaluate(() => document.documentElement.style.removeProperty('font-size'));
      if (width === 375) {
        const menu = page.getByRole('button', { name: 'Menu', exact: true });
        await menu.focus(); await page.keyboard.press('Enter');
        await page.getByRole('link', { name: 'Overview', exact: true }).focus();
        await page.keyboard.press('Escape');
        assert.equal(await menu.getAttribute('aria-expanded'), 'false');
        assert.ok(await menu.evaluate(node => node === document.activeElement));
        await page.keyboard.press('Enter');
      }
      await page.getByRole('link', { name: 'Overview', exact: true }).click();
      await page.getByRole('heading', { name: 'Keep deliveries moving.', exact: true }).waitFor();
      await page.waitForFunction(() => document.activeElement.id === 'main-content');
      if (width === 375) {
        assert.equal(await page.getByRole('button', { name: 'Menu', exact: true }).getAttribute('aria-expanded'), 'false');
        await page.getByRole('button', { name: 'Menu', exact: true }).click();
      }
      await page.getByRole('link', { name: 'Routes', exact: true }).click(); await table.waitFor();
      await page.waitForFunction(() => document.activeElement.id === 'main-content');
      await page.goBack(); await page.getByRole('heading', { name: 'Keep deliveries moving.', exact: true }).waitFor();
      await page.waitForFunction(() => document.activeElement.id === 'main-content');
      await page.goForward(); await table.waitFor();
      await page.waitForFunction(() => document.activeElement.id === 'main-content');
    }
    report.navigation = true;
    assert.equal(writes, 0); assert.deepEqual(errors, []); report.complete = true; report.writes = writes; save();
    console.log(`Admin route directory: ${report.cases.length} layout cases, keyboard selection, pagination, direct links and recovery passed.`);
  } catch (error) { if (page) await page.screenshot({ path: path.join(artifacts, 'failure.png') }).catch(() => {}); throw error; }
  finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
