const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const base = process.env.E2E_URL || "http://127.0.0.1:8081";
const out = process.env.EVIDENCE_DIR || path.resolve(__dirname, "../../evidence");
const errors = [];
const results = [];
const executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH;
let browser;
let guest, staff, admin;
let today;
const plus = (value, n) => {
  const date = new Date(value + "T12:00:00Z");
  date.setUTCDate(date.getUTCDate() + n);
  return date.toISOString().slice(0, 10);
};
async function context(width = 1440, height = 1050) {
  const context = await browser.newContext({
    viewport: { width, height },
    locale: "zh-CN",
    timezoneId: "Asia/Shanghai",
  });
  const page = await context.newPage();
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => {
    if (m.type() === "error" && !m.text().includes("status of 4")) {
      errors.push(m.text());
    }
  });
  await page.goto(base);
  await page.locator(".hero").waitFor();
  return page;
}
async function login(page, username) {
  await page.getByRole("button", { name: "登录 / 注册", exact: true }).click();
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill("DemoHotel2026!");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.locator("dialog").waitFor({ state: "hidden" });
  if (username.startsWith("staff")) {
    await page.getByRole("heading", { name: "前台工作概览" }).waitFor();
  }
  if (username === "admin") {
    await page.getByRole("heading", { name: "经营与管理概览" }).waitFor();
  }
}
async function route(page, value) {
  await page.evaluate((hash) => {
    location.hash = hash;
  }, "#" + value);
  await page.locator(".loading").waitFor({ state: "hidden" });
  await page.waitForTimeout(80);
}
async function formSubmit(page, form) {
  const error = form.locator(".form-error");
  await form.locator("button[type=submit]").click();
  await page.waitForTimeout(350);
  if ((await error.count()) && (await error.isVisible())) {
    const message = await error.innerText();
    if (message) {
      throw new Error(message);
    }
  }
}
async function book(
  page,
  {
    offset = 0,
    nights = 1,
    type = 1,
    name = "浏览器测试住客",
    modify,
    offline = false,
    student = false,
  } = {},
) {
  const query = new URLSearchParams({
    type,
    start: plus(today, offset),
    end: plus(today, offset + nights),
    guests: 1,
    rooms: 1,
  });
  if (modify) {
    query.set("modify", modify);
  }
  await route(page, (offline ? "offline" : "reserve") + "?" + query);
  const selection = page.locator("form[data-form=reserve]");
  await selection.waitFor();
  if (student) {
    await selection.locator("[name=student]").check();
  }
  await formSubmit(page, selection);
  const pay = page.locator("form[data-form=pay]");
  await pay.waitFor();
  await pay.locator("[name=contact]").fill(name);
  await pay.locator("[name=phone]").fill("13800000000");
  await pay.locator("[name=accepted]").check();
  if (student) {
    await pay.locator("[name=studentVerified]").check();
  }
  await formSubmit(page, pay);
  await page.waitForURL(/#order\?id=\d+/);
  await page.getByRole("heading", { name: /订单 #/ }).waitFor();
  return Number(new URLSearchParams(page.url().split("?")[1]).get("id"));
}
async function json(page, url, body) {
  return page.evaluate(
    async ({ url, body }) => {
      const options = { headers: {} };
      if (body) {
        const token = await (await fetch("/api/auth/csrf")).json();
        options.method = "POST";
        options.headers = {
          "Content-Type": "application/json",
          [token.headerName]: token.token,
          "Idempotency-Key": crypto.randomUUID(),
        };
        options.body = JSON.stringify(body);
      }
      const response = await fetch(url, options);
      return { status: response.status, body: await response.json() };
    },
    { url, body },
  );
}
async function check(name, fn) {
  const started = Date.now();
  try {
    await fn();
    results.push({ name, status: "PASS", milliseconds: Date.now() - started });
    console.log("PASS " + name);
  } catch (error) {
    results.push({ name, status: "FAIL", error: error.message });
    console.error("FAIL " + name + ": " + error.message);
    throw error;
  }
}
(async () => {
  fs.mkdirSync(out, { recursive: true });
  browser = await chromium.launch({
    headless: true,
    ...(executablePath ? { executablePath } : {}),
  });
  guest = await context();
  staff = await context();
  admin = await context();
  today = (await json(guest, "/api/health")).body.today;
  await check("首页清晰导航、整数人数和7人查房", async () => {
    await guest.screenshot({
      path: path.join(out, "01-home-desktop.png"),
      fullPage: true,
    });
    const guests = guest.locator("[name=guests]");
    for (const invalid of ["0", "31", "1.5"]) {
      await guests.fill(invalid);
      assert.equal(await guests.evaluate((e) => e.validity.valid), false);
    }
    await guests.fill("7");
    await guest.getByRole("button", { name: /查找空房/ }).click();
    await guest.locator(".result-hotel").first().waitFor();
    assert.ok((await guest.locator(".room-choice").count()) > 0);
    await guest.getByRole("link", { name: "校舍首页" }).click();
    await guest.locator(".hero").waitFor();
    assert.equal(
      await guest.getByRole("link", { name: "我的订单", exact: true }).count(),
      1,
    );
  });
  await login(guest, "guest");
  await login(staff, "staff1");
  await login(admin, "admin");
  await check("顾客付款→前台接单→完整入住→续住→脱敏记录", async () => {
    const id = await book(guest);
    assert.equal(
      (await json(guest, "/api/bookings/" + id)).body.status,
      "PENDING",
    );
    await route(staff, "order?id=" + id);
    await staff.getByRole("button", { name: "确认接单", exact: true }).click();
    await formSubmit(staff, staff.locator("form[data-form=confirm-order]"));
    await staff.getByRole("link", { name: "办理入住", exact: true }).click();
    const checkin = staff.locator("form[data-form=checkin]");
    await checkin.waitFor();
    await checkin.locator("[name=guest_name_0]").fill("演示入住人");
    await checkin.locator("[name=guest_phone_0]").fill("13800000000");
    await checkin.locator("[name=guest_document_0]").fill("TEST000001");
    await checkin.locator("[name=identityVerified]").check();
    await formSubmit(staff, checkin);
    await staff.getByRole("link", { name: "办理续住", exact: true }).waitFor();
    await staff
      .getByRole("button", { name: "查看住客登记", exact: true })
      .click();
    await staff.getByRole("heading", { name: "住客登记记录" }).waitFor();
    assert.ok(
      (await staff.locator("dialog").innerText()).includes("******0001"),
    );
    assert.ok(
      !(await staff.locator("dialog").innerText()).includes("TEST000001"),
    );
    await staff.getByRole("button", { name: "关闭", exact: true }).click();
    await staff.getByRole("link", { name: "办理续住", exact: true }).click();
    await formSubmit(staff, staff.locator("form[data-form=extend-quote]"));
    const extension = staff.locator("form[data-form=extend-confirm]");
    await extension.waitFor();
    await extension.locator("[name=paymentConfirmed]").check();
    await formSubmit(staff, extension);
    await staff.waitForURL(/#order/);
    const result = (await json(guest, "/api/bookings/" + id)).body;
    assert.equal(result.status, "CHECKED_IN");
    assert.equal(result.check_out, plus(today, 2));
    await route(guest, "order?id=" + id);
    await guest.screenshot({
      path: path.join(out, "02-order-detail.png"),
      fullPage: true,
    });
    await route(staff, "rooms");
    await staff.screenshot({
      path: path.join(out, "03-reception-rooms.png"),
      fullPage: true,
    });
  });
  await check("顾客取消展示并生成真实退款记录", async () => {
    const id = await book(guest, { offset: 5 });
    await guest.getByRole("button", { name: "取消预订", exact: true }).click();
    await formSubmit(guest, guest.locator("form[data-form=cancel-order]"));
    const b = (await json(guest, "/api/bookings/" + id)).body;
    assert.equal(b.status, "CANCELLED");
    assert.equal(b.payments.filter((x) => x.kind === "REFUND").length, 1);
  });
  await check("修改形成关联新单且原单保持取消", async () => {
    const original = await book(guest, { offset: 7 });
    const newer = await book(guest, { offset: 8, modify: original });
    assert.notEqual(original, newer);
    const old = (await json(guest, "/api/bookings/" + original)).body;
    const next = (await json(guest, "/api/bookings/" + newer)).body;
    assert.equal(old.status, "CANCELLED");
    assert.equal(next.parent_id, original);
  });
  await check("线下学生价预订、资料提交及管理员批准", async () => {
    const id = await book(staff, { offset: 12, offline: true, student: true });
    const b = (await json(staff, "/api/bookings/" + id)).body;
    assert.equal(b.status, "CONFIRMED");
    assert.equal(b.amount, 40000);
    await route(staff, "manage");
    await staff
      .getByRole("button", { name: "修改酒店资料", exact: true })
      .click();
    const edit = staff.locator("form[data-form=edit-info]");
    await edit
      .locator("[name=description]")
      .fill("校园里的安静住处。前台接待、独立卫浴与无线网络，方便来访和短住。");
    await formSubmit(staff, edit);
    const before = (await json(staff, "/api/catalog/hotels/1")).body;
    assert.ok(!before.description.startsWith("校园里的安静住处"));
    await route(admin, "changes");
    await admin
      .getByRole("button", { name: "批准并发布", exact: true })
      .first()
      .click();
    const review = admin.locator("form[data-form=review]");
    await review.locator("[name=note]").fill("已核对资料与展示文案。");
    await formSubmit(admin, review);
    assert.ok(
      (await json(guest, "/api/catalog/hotels/1")).body.description.startsWith(
        "校园里的安静住处",
      ),
    );
    await admin.screenshot({
      path: path.join(out, "04-administration-review.png"),
      fullPage: true,
    });
  });
  await check("应急预览、停售全退与恢复接待", async () => {
    const id = await book(guest, { offset: 15, type: 2 });
    await route(staff, "incidents");
    await staff
      .getByRole("button", { name: "登记突发事件", exact: true })
      .click();
    const preview = staff.locator("form[data-form=incident-preview]");
    await preview.locator("[name=roomTypeId]").selectOption("2");
    await preview.locator("[name=start]").fill(plus(today, 15));
    await preview.locator("[name=end]").fill(plus(today, 16));
    await preview.locator("[name=reason]").fill("测试：受影响区域临时停水");
    await formSubmit(staff, preview);
    const open = staff.locator("form[data-form=incident-open]");
    await open.locator("[name=confirmed]").check();
    await formSubmit(staff, open);
    assert.equal(
      (await json(guest, "/api/bookings/" + id)).body.status,
      "CANCELLED",
    );
    await staff
      .getByRole("button", { name: "确认恢复接待", exact: true })
      .first()
      .click();
    const close = staff.locator("form[data-form=incident-close]");
    await close.locator("[name=restored]").check();
    await formSubmit(staff, close);
  });
  await check("经营统计、日志和越权阻止", async () => {
    for (const routeName of [
      "dashboard",
      "statistics",
      "logs",
      "orders",
      "manage",
      "incidents",
    ]) {
      await route(admin, routeName);
      assert.equal(
        await admin
          .getByRole("heading", { name: "暂时无法显示这项内容" })
          .count(),
        0,
      );
    }
    const unauthorized = await json(
      guest,
      "/api/manage/statistics?hotelId=1&start=" +
        today +
        "&end=" +
        plus(today, 1),
    );
    assert.equal(unauthorized.status, 403);
    const outsider = await context();
    await login(outsider, "staff2");
    const result = await json(outsider, "/api/bookings/1");
    assert.equal(result.status, 404);
    await outsider.context().close();
  });
  await check("390像素小屏、注册登录和退出保留导航", async () => {
    const mobile = await context(390, 844);
    assert.ok(
      await mobile.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    );
    await mobile.screenshot({
      path: path.join(out, "05-home-mobile.png"),
      fullPage: true,
    });
    await mobile.getByRole("button", { name: "登录 / 注册" }).click();
    await mobile.getByRole("button", { name: "注册账号", exact: true }).click();
    const form = mobile.locator("form[data-form=register]");
    const username = "e2e_" + Date.now();
    await form.locator("[name=displayName]").fill("新住客");
    await form.locator("[name=username]").fill(username);
    await form.locator("[name=password]").fill("DemoHotel2026!");
    await formSubmit(mobile, form);
    const loginForm = mobile.locator("form[data-form=login]");
    await loginForm.locator("[name=username]").fill(username);
    await loginForm.locator("[name=password]").fill("DemoHotel2026!");
    await formSubmit(mobile, loginForm);
    await mobile.getByRole("button", { name: "退出", exact: true }).click();
    await mobile.getByRole("button", { name: "登录 / 注册" }).waitFor();
    assert.equal(
      await mobile.getByRole("link", { name: "我的订单", exact: true }).count(),
      1,
    );
    await mobile.context().close();
  });
  assert.deepEqual(
    errors,
    [],
    "No unexpected browser JavaScript or server errors",
  );
})()
  .catch((error) => {
    console.error(error.stack);
    process.exitCode = 1;
  })
  .finally(async () => {
    fs.writeFileSync(
      path.join(out, "browser-results.json"),
      JSON.stringify(
        {
          generatedAt: new Date().toISOString(),
          base,
          browserErrors: errors,
          results,
        },
        null,
        2,
      ),
    );
    if (browser) {
      if (process.exitCode) {
        for (const [name, page] of Object.entries({ guest, staff, admin })) {
          if (page) {
            await page
              .screenshot({
                path: path.join(out, "failure-" + name + ".png"),
                fullPage: true,
              })
              .catch(() => {});
          }
        }
      }
      await browser.close();
    }
  });
