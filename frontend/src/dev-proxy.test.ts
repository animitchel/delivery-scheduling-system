// @vitest-environment node
import { createServer as backendServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { createServer } from 'vite';
import { expect, it } from 'vitest';
import { developmentProxy } from '../dev-proxy';
it.each(['https://127.0.0.1:8080', 'http://external.example', 'http://user:secret@127.0.0.1', 'http://127.0.0.1/api', 'http://127.0.0.1?key=test'])('rejects unsafe development origin %s', target => {
  expect(() => developmentProxy(target)).toThrow('local HTTP origin');
});
it('forwards API/session headers and legacy paths through a real Vite server', async () => {
  const backend = backendServer((request, response) => {
    response.setHeader('Content-Type', 'application/json');
    if (request.url === '/api/auth/csrf') {
      response.setHeader('Set-Cookie', 'JSESSIONID=test-cookie; Path=/; HttpOnly; SameSite=Lax');
      response.end(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }));
    } else if (request.url === '/api/auth/logout' && request.method === 'POST') {
      const valid = request.headers['x-csrf-token'] === 'test-token' && request.headers.cookie === 'JSESSIONID=test-cookie';
      response.writeHead(valid ? 204 : 403); response.end();
    } else if (request.url === '/html/login.html') { response.setHeader('Content-Type', 'text/html'); response.end('Legacy sign in'); }
    else { response.writeHead(404); response.end('{}'); }
  });
  await new Promise<void>(resolve => backend.listen(0, '127.0.0.1', resolve));
  const backendPort = (backend.address() as AddressInfo).port;
  const vite = await createServer({ configFile: false, base: '/app/', logLevel: 'silent', server: {
    host: '127.0.0.1', port: 0, strictPort: false, proxy: developmentProxy(`http://127.0.0.1:${backendPort}`),
  } });
  try {
    await vite.listen(); const port = (vite.httpServer?.address() as AddressInfo).port;
    const origin = `http://127.0.0.1:${port}`;
    const csrf = await fetch(`${origin}/api/auth/csrf`);
    expect(csrf.headers.get('set-cookie')).toContain('HttpOnly');
    expect(await csrf.json()).toMatchObject({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
    const logout = await fetch(`${origin}/api/auth/logout`, { method: 'POST', headers: { 'X-CSRF-TOKEN': 'test-token', Cookie: 'JSESSIONID=test-cookie' } });
    expect(logout.status).toBe(204);
    expect(await (await fetch(`${origin}/html/login.html`)).text()).toBe('Legacy sign in');
    expect(await (await fetch(`${origin}/app/`)).text()).toContain('Delivery workspace');
  } finally { await vite.close(); await new Promise<void>((resolve, reject) => backend.close(error => error ? reject(error) : resolve())); }
});
