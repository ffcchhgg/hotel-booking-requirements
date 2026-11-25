const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = process.env.E2E_URL || 'http://127.0.0.1:8081';
const out = process.env.EVIDENCE_DIR || path.resolve(__dirname, '../../evidence');
(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}) });
  const errors = [];
  const record = { generatedAt: new Date().toISOString(), scenario: 'HTTP browser checkout, fee confirmation, dirty-room transition and cleaning', passed: false };
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base); await page.getByRole('button', { name: '登录 / 注册' }).click();
    await page.getByLabel('用户名', { exact: true }).fill('staff1'); await page.getByLabel('密码', { exact: true }).fill('DemoHotel2026!');
    await page.getByRole('button', { name: '登录', exact: true }).click(); await page.getByRole('heading', { name: '前台工作概览' }).waitFor();
    const fixture = await page.evaluate(async () => {
      const results = await (await fetch('/api/bookings?contact=' + encodeURIComponent('退房界面验收') + '&status=CHECKED_IN')).json(); return results.items[0];
    });
    assert.ok(fixture, 'Run checkout-fixture.sql in the isolated test database first');
    await page.evaluate(id => { location.hash = '#checkout?id=' + id; }, fixture.id);
    const form = page.locator('form[data-form=checkout]'); await form.waitFor();
    await form.locator('[name=paymentConfirmed]').check(); await page.screenshot({ path: path.join(out, '06-checkout-confirmation.png'), fullPage: true });
    await form.locator('button[type=submit]').click(); await page.waitForURL(/#order\?id=/);
    const result = await page.evaluate(async id => (await (await fetch('/api/bookings/' + id)).json()), fixture.id);
    assert.equal(result.status, 'CHECKED_OUT'); assert.ok(result.stays[0].ended_at);
    await page.evaluate(() => { location.hash = '#rooms'; });
    const room = page.locator('.room-tile').filter({ has: page.getByText(result.stays[0].number, { exact: true }) }); await room.waitFor();
    assert.ok((await room.innerText()).includes('待清洁')); await room.getByRole('button', { name: '确认清洁完成' }).click();
    await page.locator('form[data-form=clean] button[type=submit]').click();
    await page.locator('dialog').waitFor({ state: 'hidden' });
    await page.waitForFunction(number => [...document.querySelectorAll('.room-tile')].some(el => el.querySelector('strong')?.textContent === number && el.textContent.includes('可安排')), result.stays[0].number);
    assert.deepEqual(errors, []); record.passed = true; record.orderId = fixture.id; record.room = result.stays[0].number; record.lateFee = result.payments.find(p => p.kind === 'LATE_FEE')?.amount || 0;
    console.log('PASS browser checkout → dirty room → cleaned room');
  } catch (error) { record.error = error.message; throw error; }
  finally { fs.writeFileSync(path.join(out, 'checkout-results.json'), JSON.stringify(record, null, 2)); await browser.close(); }
})().catch(e => { console.error(e.stack); process.exitCode = 1; });
