const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const assert = require("node:assert/strict");
const { performance } = require("node:perf_hooks");
const base = process.env.E2E_URL || "http://127.0.0.1:8081";
class Client {
  constructor() {
    this.cookies = new Map();
  }
  async request(url, body) {
    const headers = {
      Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; "),
    };
    if (body) {
      headers["Content-Type"] = "application/json";
      headers[this.token.headerName] = this.token.token;
    }
    const response = await fetch(base + url, {
      headers,
      ...(body ? { method: "POST", body: JSON.stringify(body) } : {}),
    });
    for (const cookie of response.headers.getSetCookie()) {
      const [key, ...value] = cookie.split(";")[0].split("=");
      this.cookies.set(key, value.join("="));
    }
    return { status: response.status, body: await response.json() };
  }
  async login() {
    this.token = (await this.request("/api/auth/csrf")).body;
    assert.equal(
      (
        await this.request("/api/auth/login", {
          username: "admin",
          password: "DemoHotel2026!",
        })
      ).status,
      200,
    );
  }
}
(async () => {
  const clients = await Promise.all(
    Array.from({ length: 20 }, async () => {
      const c = new Client();
      await c.login();
      return c;
    }),
  );
  const data = (await clients[0].request("/api/bookings")).body;
  assert.ok(
    data.total >= 10000,
    "Seed at least 10000 real database orders before running the benchmark",
  );
  const today = (await clients[0].request("/api/health")).body.today;
  const d = new Date(today + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + 2);
  const end = d.toISOString().slice(0, 10);
  const endpoints = [
    "/api/bookings?page=1",
    "/api/bookings?status=NO_SHOW&page=40",
    "/api/bookings?contact=" + encodeURIComponent("性能数据-9"),
    "/api/catalog/search?" +
      new URLSearchParams({ start: today, end, guests: 7 }),
    "/api/bookings?hotelId=1&dateType=check_in&page=200",
  ];
  for (const endpoint of endpoints) {
    assert.equal((await clients[0].request(endpoint)).status, 200);
  }
  const results = [];
  const started = performance.now();
  // 20 authenticated sessions, each executes 5 requests; 100 measured HTTP requests total.
  await Promise.all(
    clients.map(async (client, worker) => {
      for (let index = 0; index < 5; index++) {
        const endpoint = endpoints[(worker + index) % endpoints.length];
        const before = performance.now();
        try {
          const result = await client.request(endpoint);
          results.push({
            worker,
            index,
            endpoint,
            status: result.status,
            milliseconds: Math.round((performance.now() - before) * 100) / 100,
            valid:
              result.status === 200 &&
              (Array.isArray(result.body) || Array.isArray(result.body.items)),
          });
        } catch (error) {
          results.push({
            worker,
            index,
            endpoint,
            valid: false,
            milliseconds: Math.round(performance.now() - before),
            error: error.message,
          });
        }
      }
    }),
  );
  const sorted = results.map((r) => r.milliseconds).sort((a, b) => a - b);
  const passing = results.filter(
    (r) => r.valid && r.milliseconds <= 2000,
  ).length;
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
    concurrency: 20,
    requests: 100,
    passingWithin2Seconds: passing,
    p95Milliseconds: sorted[94],
    maximumMilliseconds: sorted[99],
    wallMilliseconds: Math.round(performance.now() - started),
    passed: passing >= 95,
    scope:
      "Local HTTP requests including network, session authorization, SQL and JSON response parsing; five warmed-up query shapes; no claim about production load.",
    results,
  };
  fs.writeFileSync(
    path.resolve(__dirname, "../../evidence/performance-results.json"),
    JSON.stringify(report, null, 2),
  );
  console.log(JSON.stringify({ ...report, results: undefined }, null, 2));
  assert.ok(
    report.passed,
    "At least 95 of 100 queries must succeed within 2 seconds",
  );
})().catch((e) => {
  console.error(e.stack);
  process.exitCode = 1;
});
