import type { ProxyOptions } from 'vite';
export function developmentProxy(target: string): Record<string, ProxyOptions> {
  const backend = new URL(target);
  if (!['localhost', '127.0.0.1', '[::1]'].includes(backend.hostname) || backend.protocol !== 'http:'
    || backend.username || backend.password || backend.pathname !== '/' || backend.search || backend.hash) {
    throw new Error('The development backend must be a local HTTP origin.');
  }
  return Object.fromEntries(['/api', '/html', '/js', '/css'].map(path => [path, { target: backend.origin, changeOrigin: false }]));
}
