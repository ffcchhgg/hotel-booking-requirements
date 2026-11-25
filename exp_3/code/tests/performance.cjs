const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const assert = require('node:assert/strict');
const { performance } = require('node:perf_hooks');
const base = process.env.E2E_URL || 'http://127.0.0.1:8081';
const evidence = process.env.EVIDENCE_DIR || path.resolve(__dirname, '../../evidence');
const concurrency = 20;
const deadlineMs = 2000;

class Client {
  constructor() {
    this.cookies = new Map();
  }
  async request(url, body) {
    const headers = {
      Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; '),
    };
    if (body) {
      headers['Content-Type'] = 'application/json';
      headers[this.token.headerName] = this.token.token;
    }
    const response = await fetch(base + url, {
      headers,
      signal: AbortSignal.timeout(5000),
      ...(body ? { method: 'POST', body: JSON.stringify(body) } : {}),
    });
    for (const cookie of response.headers.getSetCookie()) {
      const [key, ...value] = cookie.split(';')[0].split('=');
      this.cookies.set(key, value.join('='));
    }
    return { status: response.status, body: await response.json() };
  }
  async login(username) {
    this.token = (await this.request('/api/auth/csrf')).body;
    assert.equal(
      (
        await this.request('/api/auth/login', {
          username,
          password: 'DemoHotel2026!',
        })
      ).status,
      200,
    );
  }
}

function dateAfter(today, days) {
  const date = new Date(today + 'T12:00:00Z');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function validRooms(body, criteria) {
  if (!Array.isArray(body) || (body.length === 0) !== criteria.expectEmpty) return false;
  return body.every(
    (room) =>
      Number.isInteger(room.id) &&
      room.id > 0 &&
      Number.isInteger(room.capacity) &&
      room.capacity > 0 &&
      room.rooms === Math.ceil(criteria.query.guests / room.capacity) &&
      Number.isInteger(room.available) &&
      room.available >= room.rooms &&
      room.nights === criteria.nights &&
      Number.isInteger(room.total) &&
      room.total > 0 &&
      (!criteria.query.campus || room.campus === criteria.query.campus) &&
      (!criteria.query.type || room.name === criteria.query.type) &&
      (!criteria.query.budget || room.total <= criteria.query.budget),
  );
}

(async () => {
  const admin = new Client();
  await admin.login('admin');
  const data = (await admin.request('/api/bookings')).body;
  assert.ok(
    data.total >= 10000,
    'Seed at least 10000 real database orders before running the benchmark',
  );
  const today = (await admin.request('/api/health')).body.today;
  const criteria = [
    {
      name: 'single-night',
      nights: 1,
      query: { start: today, end: dateAfter(today, 1), guests: 1 },
    },
    {
      name: 'large-party',
      nights: 2,
      query: { start: today, end: dateAfter(today, 2), guests: 7 },
    },
    {
      name: 'campus-and-type',
      nights: 3,
      query: {
        start: dateAfter(today, 2),
        end: dateAfter(today, 5),
        guests: 3,
        campus: '仙林校区',
        type: '家庭套房',
      },
    },
    {
      name: 'budget',
      nights: 1,
      query: { start: dateAfter(today, 5), end: dateAfter(today, 6), guests: 2, budget: 50000 },
    },
    {
      name: 'empty-result',
      nights: 1,
      expectEmpty: true,
      query: { start: dateAfter(today, 6), end: dateAfter(today, 7), guests: 1, budget: 1 },
    },
  ];
  for (const item of criteria) {
    item.expectEmpty ??= false;
    item.endpoint = '/api/catalog/search?' + new URLSearchParams(item.query);
  }
  const clients = await Promise.all(
    Array.from({ length: concurrency }, async () => {
      const client = new Client();
      await client.login('guest');
      return client;
    }),
  );
  const sessions = clients.map((client) => client.cookies.get('JSESSIONID'));
  assert.ok(
    sessions.every(Boolean) && new Set(sessions).size === concurrency,
    'Expected 20 distinct sessions',
  );
  for (const item of criteria) {
    const warmup = await clients[0].request(item.endpoint);
    assert.ok(
      warmup.status === 200 && validRooms(warmup.body, item),
      `Unexpected warmup response: ${item.name}`,
    );
  }

  const results = [];
  const started = performance.now();
  for (let round = 0; round < criteria.length; round++) {
    const batch = await Promise.all(
      clients.map(async (client, worker) => {
        const item = criteria[(worker + round) % criteria.length];
        const request = {
          round: round + 1,
          worker: worker + 1,
          scenario: item.name,
          endpoint: item.endpoint,
        };
        const before = performance.now();
        try {
          const { status, body } = await client.request(item.endpoint);
          const milliseconds = performance.now() - before;
          const valid = status === 200 && validRooms(body, item);
          return {
            ...request,
            status,
            resultCount: Array.isArray(body) ? body.length : null,
            milliseconds,
            valid,
            withinLimit: valid && milliseconds <= deadlineMs,
          };
        } catch (error) {
          return {
            ...request,
            status: null,
            milliseconds: performance.now() - before,
            valid: false,
            withinLimit: false,
            error: error.message,
          };
        }
      }),
    );
    results.push(...batch);
  }
  assert.equal(results.length, 100);
  const sorted = results.map((r) => r.milliseconds).sort((a, b) => a - b);
  const passing = results.filter((result) => result.withinLimit).length;
  const report = {
    generatedAt: new Date().toISOString(),
    base,
    platform: os.platform(),
    release: os.release(),
    cpu: os.cpus()[0].model,
    logicalCpus: os.cpus().length,
    memoryGiB: Math.round(os.totalmem() / 2 ** 30),
    node: process.version,
    totalOrders: data.total,
    concurrency,
    requests: results.length,
    warmupRequests: criteria.length,
    deadlineMs,
    passingWithin2Seconds: passing,
    p95Milliseconds: sorted[94],
    maximumMilliseconds: sorted[99],
    wallMilliseconds: Math.round(performance.now() - started),
    passed: passing >= 95,
    scope:
      'REQ-026 room search only. Five rounds of twenty authenticated sessions, including HTTP and JSON response parsing. Login, order-count verification and warmups excluded.',
    criteria,
    results,
  };
  fs.mkdirSync(evidence, { recursive: true });
  fs.writeFileSync(
    path.join(evidence, 'room-search-results.json'),
    JSON.stringify(report, null, 2),
  );
  console.log(JSON.stringify({ ...report, results: undefined }, null, 2));
  assert.ok(report.passed, 'At least 95 of 100 queries must succeed within 2 seconds');
})().catch((e) => {
  console.error(e.stack);
  process.exitCode = 1;
});
