import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const base = (process.argv[2] ?? 'http://127.0.0.1:18080').replace(/\/$/, '');
if (!['localhost', '127.0.0.1', '::1'].includes(new URL(base).hostname)) {
  throw new Error('Use a local diagnostic API with synthetic accounts.');
}
const email = `diagnostic.${randomUUID()}@example.invalid`;
const password = ' Synthetic_123_ñ🩺 ';

async function call(path, body, expected, access) {
  const response = await fetch(`${base}/api/v1${path}`, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', ...(access ? { Authorization: `Bearer ${access}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  const data = response.status === 204 ? null : await response.json();
  console.log(`${path} -> ${response.status}`);
  assert.equal(response.status, expected, `Unexpected status at ${path}`);
  return data;
}

await call('/auth/register', { email, password, name: 'Synthetic Diagnostic' }, 201);
const session = await call('/auth/login', { email: email.toUpperCase(), password }, 200);
await call('/users/me', undefined, 200, session.accessToken);
await call('/auth/login', { email, password: password.trim() }, 401);
await call('/auth/register', { email, password, name: 'Duplicate' }, 409);
const rotated = await call('/auth/refresh', { refreshToken: session.refreshToken }, 200);
await call('/auth/refresh', { refreshToken: session.refreshToken }, 401);
await call('/auth/logout', { refreshToken: rotated.refreshToken }, 204);
await call('/auth/refresh', { refreshToken: rotated.refreshToken }, 401);
await call('/auth/login', { email, password }, 200);
console.log('Synthetic register, login, rotation, logout and login passed.');
