// Input boundaries and a complete group booking through visible UI controls.
const {chromium,expect,launchOptions}=require('./browser_runtime.cjs');
const fs=require('fs'),path=require('path'),crypto=require('crypto'),{pathToFileURL}=require('url');
const root=path.resolve(__dirname,'..');
(async()=>{
 const browser=await chromium.launch(launchOptions);
 const page=await browser.newPage({viewport:{width:1440,height:1000},timezoneId:'Asia/Shanghai'});
 const errors=[],checks=[];page.on('pageerror',e=>errors.push(e.message));
 const state=()=>page.evaluate(()=>campusPrototype.snapshot());
 await page.goto(pathToFileURL(path.join(root,'prototype/index.html')).href);
 const input=page.getByRole('spinbutton',{name:'入住人数'});await expect(input).toHaveAttribute('min','1');await expect(input).toHaveAttribute('max','30');
 for(const value of ['','0','-1','2.5','31','1e1']){
  await input.fill(value);await page.locator('#search-form button').click();
  await expect(page.locator('#search-error')).toContainText('1—30 之间的整数');expect((await state()).search.guests).toBe(2);
 }
 for(const value of ['1','30']){
  await input.fill(value);await page.locator('#search-form button').click();await expect(page.locator('h1')).toHaveText('适合这次停留的房间');
  expect((await state()).search.guests).toBe(Number(value));await expect(input).toHaveValue(value);
 }
 await input.fill('6');await input.press('ArrowUp');await expect(input).toHaveValue('7');
 checks.push('人数空值、0、负数、小数、31、指数写法被拦截；1和30可查询；上下箭头可调整');
 await page.locator('#search-form button').click();await expect(page.locator('.hotel-card')).toHaveCount(1);await expect(page.locator('.hotel-card')).toContainText('书庭居');await expect(page.locator('.hotel-card')).toContainText('4,080');await expect(page.locator('.hotel-card')).toContainText('3 间');
 await page.locator('select[name=budget]').selectOption('1400');await expect(page.locator('.hotel-card')).toHaveCount(0);await page.locator('select[name=budget]').selectOption('all');
 await page.getByRole('link',{name:/查看房型/}).click();await expect(page.locator('.room-row')).toContainText('7 位住客至少需要 3 间');await expect(page.locator('.room-action')).toContainText('4,080');
 await page.getByRole('link',{name:'选择此房型'}).click();await expect(page.locator('#booking-form')).toBeVisible();await expect(page.locator('[name=quantity]')).toHaveValue('3');await expect(page.locator('[name=quantity]')).toHaveAttribute('min','3');await expect(page.locator('[name=quantity]')).toHaveAttribute('max','4');await expect(page.locator('#quote-total')).toHaveText('¥ 4,080.00');
 checks.push('7人按每间3人选择3间，查询/详情/结算均为两晚4080元，预算按全部房间过滤');
 await page.locator('[name=quantity]').fill('4');await expect(page.locator('#quote-total')).toHaveText('¥ 5,440.00');await page.locator('[name=guest]').fill('团体联系人');await page.locator('[name=phone]').fill('13800000000');await page.locator('[name=agree]').check();await page.locator('#booking-form button[type=submit]').click();
 await expect(page.locator('h1')).toHaveText('欢迎回来');await page.getByRole('button',{name:/使用演示账号体验/}).click();await expect(page.locator('#booking-form')).toBeVisible();await expect(page.locator('[name=quantity]')).toHaveValue('4');await expect(page.locator('[name=guest]')).toHaveValue('团体联系人');await expect(page.locator('#quote-total')).toHaveText('¥ 5,440.00');
 const before=(await state()).orders.length;
 // Bypass HTML constraint validation to ensure submission logic also rejects insufficient capacity.
 await page.locator('[name=quantity]').fill('2');await page.locator('#booking-form').evaluate(f=>{f.noValidate=true;f.requestSubmit()});await expect(page.locator('#booking-error')).toContainText('须容纳全部住客');expect((await state()).orders.length).toBe(before);
 await page.locator('[name=quantity]').fill('5');await page.locator('#booking-form button[type=submit]').click();await expect(page.locator('#booking-error')).toContainText('不能超过');expect((await state()).orders.length).toBe(before);
 await page.locator('[name=quantity]').fill('3');await page.locator('#booking-form button[type=submit]').click();await expect(page.locator('h1')).toHaveText('付款成功，等待酒店确认');const order=(await state()).orders[0];expect(order.quantity).toBe(3);expect(order.guestCount).toBe(7);expect(order.amount).toBe(4080);await expect(page.locator('#main')).toContainText('预订入住人数');
 checks.push('房间数量可增加且总价联动；登录返回保留数量；数量不足或超库存不能创建订单；付款保留7人3间');
 // A single sold-out/short night invalidates the entire group stay.
 await page.evaluate(()=>{location.hash='home';campusPrototype.reset();campusPrototype.fixture(m=>{m.inventory['h3/r1/'+m.search.start]={capacity:2,price:680};});});await expect(page.locator('h1')).toContainText('下一站');await input.fill('7');await page.locator('#search-form button').click();await expect(page.locator('h1')).toHaveText('适合这次停留的房间');await expect(page.locator('.hotel-card')).toHaveCount(0);
 checks.push('仅有一晚不足3间时，7人查询不会错误显示整段可订');
 await page.evaluate(()=>{location.hash='home';campusPrototype.reset()});await expect(page.locator('h1')).toContainText('下一站');
 const out=path.join(root,'work/guest_count');fs.mkdirSync(out,{recursive:true});
 for(const width of [1440,390]){await page.setViewportSize({width,height:1000});await input.fill('30');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.locator('#search-form').screenshot({path:path.join(out,'search_'+width+'.png')});}
 expect(errors).toEqual([]);checks.push('桌面1440与手机390数字输入和范围说明无整页溢出');
 const source_sha256=Object.fromEntries(['prototype/app.js','prototype/style.css'].map(f=>[f,crypto.createHash('sha256').update(fs.readFileSync(path.join(root,f))).digest('hex')]));
 fs.writeFileSync(path.join(root,'checks/guest_count.json'),JSON.stringify({result:'PASS',checks,source_sha256,scope:'人数输入与单房型多间预订的浏览器交互验证'},null,2));
 await browser.close();console.log(JSON.stringify({result:'PASS',checks},null,2));
})().catch(e=>{console.error(e);process.exit(1)});
