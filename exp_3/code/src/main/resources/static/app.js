// All business results come from the server. Browser state only holds the current view.
export const state = {
  user: null,
  today: "",
  hotels: [],
  csrf: null,
  route: "",
  query: new URLSearchParams(),
  quote: null,
  order: null,
  manage: null,
};
export const $ = (selector, root = document) => root.querySelector(selector);
export const $$ = (selector, root = document) => [
  ...root.querySelectorAll(selector),
];
export const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
export const money = (cents) =>
  "¥" +
  (Number(cents) / 100).toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
export const day = (date, offset) => {
  const d = new Date(date + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + Number(offset));
  return d.toISOString().slice(0, 10);
};
export const time = (value) =>
  value
    ? new Date(value).toLocaleString("zh-CN", {
        timeZone: "Asia/Shanghai",
        hour12: false,
      })
    : "—";
export const role = (value) => state.user?.role === value;
export const label = (value) =>
  ({
    PENDING: "待酒店确认",
    CONFIRMED: "待入住",
    CHECKED_IN: "入住中",
    CHECKED_OUT: "已退房",
    CANCELLED: "已取消",
    NO_SHOW: "未到店",
    READY: "可安排",
    OCCUPIED: "在住",
    DIRTY: "待清洁",
    DISABLED: "停用",
    APPROVED: "已批准",
    REJECTED: "已驳回",
    OPEN: "处理中",
    CLOSED: "已关闭",
    REFUNDED: "已全额退款",
    MANUAL: "待人工处理",
    RESOLVED: "已办结",
    PAYMENT: "预订付款",
    REFUND: "退款",
    EXTENSION: "续住补款",
    LATE_FEE: "超时费",
    CANCELLED_OLD: "原单已取消，待完成新单",
    CREATED_NEW: "已生成新单",
    FAILED: "新付款失败",
    EARLY_DEPARTURE: "提前退房",
    OVERDUE_EXTENSION: "超期续住",
    OTHER: "其他接待事项",
    HOTEL: "酒店资料",
    ROOM_TYPE: "房型资料",
    INVENTORY: "房价与房量",
  })[value] || value;
export const badge = (value) =>
  `<span class="badge ${["PENDING", "MANUAL", "DIRTY", "OPEN"].includes(value) ? "pending" : ["CANCELLED", "CHECKED_OUT", "NO_SHOW", "CLOSED", "REJECTED"].includes(value) ? "closed" : ""}">${esc(label(value))}</span>`;
export const href = (route, values = {}) =>
  "#" +
  route +
  (Object.keys(values).length ? "?" + new URLSearchParams(values) : "");
export const go = (route, values = {}) => {
  const target = href(route, values);
  if (location.hash === target) {
    render();
  } else {
    location.hash = target;
  }
};
export const fields = (form) => Object.fromEntries(new FormData(form));
export const checked = (form, name) =>
  Boolean(form.elements.namedItem(name)?.checked);
export const hidden = (name, value) =>
  `<input type="hidden" name="${esc(name)}" value="${esc(value)}">`;
export const option = (value, text, selected) =>
  `<option value="${esc(value)}" ${String(value) === String(selected) ? "selected" : ""}>${esc(text)}</option>`;
export const errorBox = '<div class="form-error" role="alert"></div>';
export const button = (action, text, id = "", style = "secondary") =>
  `<button type="button" class="btn ${style}" data-action="${action}" data-id="${esc(id)}">${esc(text)}</button>`;
export const heading = (title, subtitle, action = "") =>
  `<div class="section-heading"><div><div class="eyebrow">CAMPUS STAY</div><h1>${esc(title)}</h1><p class="subheading">${esc(subtitle)}</p></div>${action}</div>`;
export const empty = (title, description, action = "") =>
  `<div class="empty"><h3>${esc(title)}</h3><p class="muted">${esc(description)}</p>${action ? `<div class="actions">${action}</div>` : ""}</div>`;
export const table = (headers, rows) =>
  `<div class="table-wrap"><table><thead><tr>${headers.map((x) => `<th>${esc(x)}</th>`).join("")}</tr></thead><tbody>${rows.join("")}</tbody></table></div>`;
export const keyvals = (entries) =>
  `<dl class="keyvals">${entries.map(([key, value]) => `<div><dt>${esc(key)}</dt><dd>${esc(value)}</dd></div>`).join("")}</dl>`;
export const notice = (text, warning = false) =>
  `<div class="notice ${warning ? "warning" : ""}">${esc(text)}</div>`;
export function toast(message) {
  const element = $("#toast");
  element.textContent = message;
  element.classList.add("visible");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => element.classList.remove("visible"), 4500);
}
export async function api(path, body, key, method = "POST") {
  const options = {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  };
  if (body !== undefined) {
    if (!state.csrf) {
      state.csrf = await api("/api/auth/csrf");
    }
    options.method = method;
    options.headers[state.csrf.headerName] = state.csrf.token;
    if (key) {
      options.headers["Idempotency-Key"] = key;
    }
    if (body instanceof FormData) {
      options.body = body;
    } else {
      options.headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(body);
    }
  }
  let response;
  try {
    response = await fetch(path, options);
  } catch {
    throw new Error(
      "暂时无法连接服务。请检查连接后重试；重复提交会保留同一请求编号。",
    );
  }
  const result =
    response.status === 204 ? {} : await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && !path.endsWith("/login")) {
      state.user = null;
      shell();
    }
    throw new Error(
      result.message || `操作未完成（${response.status}），请刷新后重试。`,
    );
  }
  return result;
}
export async function mutation(path, body, form) {
  const payload = JSON.stringify(body);
  if (form && form.dataset.payload !== payload) {
    form.dataset.key = crypto.randomUUID();
    form.dataset.payload = payload;
  }
  return api(path, body, form?.dataset.key || crypto.randomUUID());
}
export function modal(title, content) {
  const dialog = $("#dialog");
  dialog.innerHTML = `<div class="modal-header"><h2 id="dialog-title">${esc(title)}</h2><button type="button" class="modal-close" data-action="close" aria-label="关闭">×</button></div>${content}`;
  if (!dialog.open) {
    dialog.showModal();
  }
}
export function closeModal() {
  $("#dialog").close();
}
export function loginDialog() {
  modal(
    "登录校舍",
    `<form data-form="login" class="stack"><p class="muted">使用你的账号继续预订或进入酒店工作台。</p><label>用户名<input name="username" required maxlength="40" autocomplete="username" autofocus></label><label>密码<input name="password" type="password" required maxlength="72" autocomplete="current-password"></label>${errorBox}<button class="btn full" type="submit">登录</button><div class="price-line"><small>还没有顾客账号？</small><button type="button" class="link-button" data-action="register">注册账号</button></div><p class="technical-note">教学演示账号及密码见项目 README。前台和管理员账号由系统预置，注册入口仅创建顾客账号。</p></form>`,
  );
}
function registerDialog() {
  modal(
    "注册顾客账号",
    `<form data-form="register" class="stack"><label>姓名或称呼<input name="displayName" required maxlength="60" autocomplete="name"></label><label>用户名<input name="username" required pattern="[a-zA-Z][a-zA-Z0-9_]{3,39}" minlength="4" maxlength="40" autocomplete="username"><span class="form-note">4—40位字母、数字或下划线，以字母开头。</span></label><label>密码<input name="password" type="password" required minlength="10" maxlength="72" autocomplete="new-password"><span class="form-note">至少10个字符；不要使用其他网站的真实密码。</span></label>${errorBox}<button type="submit" class="btn">创建账号</button><button type="button" class="link-button" data-action="login">已有账号，去登录</button></form>`,
  );
}
function shell() {
  const work = role("STAFF") || role("ADMIN");
  $("#header").innerHTML =
    `<div class="header-inner"><a class="brand" href="#home" aria-label="校舍首页"><span class="icon-house">⌂</span><span class="brand-name">校舍<small>CAMPUS STAY</small></span></a><nav class="header-nav" aria-label="主要导航"><a href="#home" class="${state.route === "home" ? "active" : ""}">查找酒店</a>${!work ? '<a href="#orders">我的订单</a><a href="#guide" class="extra">预订须知</a>' : `<a href="#dashboard">${role("STAFF") ? "前台工作台" : "管理工作台"}</a>`}${state.user ? `<span class="account"><span class="avatar">${esc(state.user.displayName.slice(0, 1))}</span><b>${esc(state.user.displayName)}</b><button type="button" class="logout" data-action="logout">退出</button></span>` : '<button type="button" class="btn" data-action="login">登录 / 注册</button>'}</nav></div>`;
  const showSidebar =
    work && !["home", "search", "hotel", "guide"].includes(state.route);
  $("#shell").className = showSidebar ? "work-layout" : "";
  $("#sidebar").hidden = !showSidebar;
  if (showSidebar) {
    const links = role("STAFF")
      ? [
          ["dashboard", "⌂", "工作概览"],
          ["orders", "▤", "订单接待"],
          ["offline", "+", "到店预订"],
          ["rooms", "▦", "客房与清洁"],
          ["manage", "◇", "资料与房量"],
          ["changes", "✓", "申请记录"],
          ["incidents", "!", "应急处理"],
        ]
      : [
          ["dashboard", "⌂", "经营概览"],
          ["orders", "▤", "订单查询"],
          ["changes", "✓", "资料审核"],
          ["manage", "◇", "酒店配置"],
          ["statistics", "▥", "经营统计"],
          ["incidents", "!", "应急记录"],
          ["logs", "≡", "操作日志"],
        ];
    $("#sidebar").innerHTML =
      `<div class="side-label">${role("STAFF") ? "HOTEL RECEPTION" : "ADMINISTRATION"}</div>` +
      links
        .map(
          ([route, symbol, text]) =>
            `<a href="#${route}" class="${state.route === route ? "active" : ""}"><span class="side-symbol">${symbol}</span>${text}</a>`,
        )
        .join("");
  }
}
export function searchFields(values = {}, prefix = "") {
  const start = values.start || state.today,
    end = values.end || day(start, 1);
  return `<label>校区<select name="campus">${option("", "全部校区", values.campus)}${[...new Set(state.hotels.map((h) => h.campus))].map((c) => option(c, c, values.campus)).join("")}</select></label><label>入住日期<input type="date" name="start" value="${esc(start)}" min="${state.today}" max="${day(state.today, 364)}" required aria-label="${prefix}入住日期"></label><label>离店日期<input type="date" name="end" value="${esc(end)}" min="${day(start, 1)}" max="${day(state.today, 365)}" required aria-label="${prefix}离店日期"></label><label>入住人数<input type="number" name="guests" value="${esc(values.guests || 1)}" min="1" max="30" step="1" required inputmode="numeric"><span class="form-note">1—30位</span></label>`;
}
async function homePage() {
  return `<section class="hero"><div class="hero-copy"><div class="eyebrow">A STAY CLOSE TO CAMPUS</div><h1>住在校园旁，<br>安心赴约。</h1><p>来访、相聚，或是短暂停留。<br>在熟悉的校园里，找到一处舒适落脚点。</p><div class="hero-label"><span>校内酒店</span><span>房价透明</span><span>前台接待</span></div></div><img src="/assets/garden.jpg" alt="掩映在庭院绿意中的酒店"></section><form class="search-panel" data-form="search"><div class="search-grid">${searchFields()}<button class="btn" type="submit">查找空房 <span aria-hidden="true">→</span></button></div><p class="search-note">按整个行程核对余房，支持多人、多间预订。付款后需酒店确认，最终入住安排以订单状态为准。</p>${errorBox}</form><section><div class="section-heading"><div><div class="eyebrow">OUR CAMPUS HOTELS</div><h2>校园里的安心之选</h2></div><small>${state.hotels.length} 家酒店 · 统一预订服务</small></div><div class="hotel-grid">${state.hotels.map((h) => `<article class="hotel-card"><a href="${href("hotel", { id: h.id })}" class="photo" tabindex="-1"><img src="${esc(h.image)}" alt="${esc(h.name)}" loading="lazy"><span class="badge">${esc(h.campus)}</span></a><div class="hotel-card-body"><h3><a href="${href("hotel", { id: h.id })}">${esc(h.name)}</a></h3><p>${esc(h.description)}</p><div class="hotel-card-bottom"><span class="muted">到店核验 · 前台服务</span><a href="${href("hotel", { id: h.id })}">查看酒店 →</a></div></div></article>`).join("")}</div></section><div class="trust-row"><div class="trust-item"><span class="trust-number">01</span><div><h4>一次查清整个行程</h4><p>连续多晚核对房量，价格逐晚列明。</p></div></div><div class="trust-item"><span class="trust-number">02</span><div><h4>每一步都有订单记录</h4><p>从付款到酒店确认，随时查看进度。</p></div></div><div class="trust-item"><span class="trust-number">03</span><div><h4>入住前看清取消规则</h4><p>操作前明确展示退款金额与生效结果。</p></div></div></div>`;
}
async function searchPage(q) {
  const values = Object.fromEntries(q);
  const results = await api("/api/catalog/search?" + q);
  const groups = state.hotels
    .map((h) => ({ ...h, choices: results.filter((r) => r.hotel_id === h.id) }))
    .filter((h) => h.choices.length);
  return `${heading("为这次行程，选一处好住处", `${values.start} 至 ${values.end} · ${values.guests} 位住客`)}<form class="card stack" data-form="search"><div class="search-grid">${searchFields(values)}<button class="btn" type="submit">重新查房</button></div><div class="filter"><label>房型<select name="type">${option("", "全部房型", values.type)}${["舒适大床房", "雅致双床房", "家庭套房"].map((x) => option(x, x, values.type)).join("")}</select></label><label>行程总预算（元，可留空）<input name="budgetYuan" type="number" min="1" max="10000000" step="1" value="${esc(values.budget ? Number(values.budget) / 100 : "")}" placeholder="不限"></label><p class="technical-note">金额为建议房间数量的全部住宿费用。<br>预订时可自行调整房间数量。</p></div>${errorBox}</form><div class="section-heading"><p class="muted">找到 ${groups.length} 家酒店、${results.length} 种可订房型</p><small>以确认付款时重新核验的房价与房量为准</small></div><div class="stack">${groups.length ? groups.map((h) => `<article class="result-hotel"><div class="result-head"><img src="${esc(h.image)}" alt="${esc(h.name)}"><div><span class="badge">${esc(h.campus)}</span><h2>${esc(h.name)}</h2><p>${esc(h.description)}</p><a class="link-button" href="${href("hotel", { id: h.id })}">酒店详情 →</a></div></div>${h.choices.map((r) => `<div class="room-choice"><div><h3>${esc(r.name)}</h3><small>每间最多 ${r.capacity} 位 · 建议 ${r.rooms} 间 · ${r.nights} 晚</small><small>全程余房 ${r.available} 间 · ${esc(r.description)}</small></div><div><div class="money">${money(r.total)}</div><small>${r.rooms} 间全程总价</small></div><div><a class="btn" href="${href("reserve", { type: r.id, start: values.start, end: values.end, guests: values.guests, rooms: r.rooms })}">选择房型</a></div></div>`).join("")}</article>`).join("") : empty("暂时没有匹配的空房", "可尝试调整日期、校区、人数或预算，再次查询。")}</div>`;
}
async function hotelPage(q) {
  const h = await api("/api/catalog/hotels/" + Number(q.get("id")));
  return `<div class="breadcrumbs"><a href="#home">首页</a><span>/</span><span>${esc(h.name)}</span></div><section class="hotel-hero"><img src="${esc(h.image)}" alt="${esc(h.name)}"><div><div class="eyebrow">${esc(h.campus)} CAMPUS</div><h1>${esc(h.name)}</h1><p>${esc(h.description)}</p>${keyvals(
    [
      ["接待方式", "前台核验身份后入住"],
      ["预订方式", "在线预订 / 到店办理"],
      ["退房时间", "原定离店日12:00前"],
      ["费用说明", "按实际行程逐晚报价"],
    ],
  )}<a class="btn" href="${href("search", { start: state.today, end: day(state.today, 1), guests: 1, campus: h.campus })}">查找这里的空房</a></div></section><div class="section-heading"><h2>房型一览</h2><small>具体房价随入住日期查询</small></div><div class="grid3">${h.roomTypes.map((r) => `<article class="card"><img class="room-photo" src="${esc(r.image)}" alt="${esc(r.name)}"><h3>${esc(r.name)}</h3><p class="subheading">${esc(r.description)}</p><p class="muted">每间最多 ${r.capacity} 位住客</p><a class="link-button" href="${href("reserve", { type: r.id, start: state.today, end: day(state.today, 1), guests: 1, rooms: 1 })}">选择日期与房间 →</a></article>`).join("")}</div>`;
}
async function findType(id) {
  for (const hotel of state.hotels) {
    const h = await api("/api/catalog/hotels/" + hotel.id);
    const type = h.roomTypes.find((r) => r.id === Number(id));
    if (type) {
      return { hotel: h, type };
    }
  }
  throw new Error("房型不存在或已下架，请重新查房。");
}
const steps = (current) =>
  `<div class="stepper"><span class="${current === 1 ? "current" : ""}"><b>1</b>选择行程</span><i></i><span class="${current === 2 ? "current" : ""}"><b>2</b>核对与付款</span><i></i><span class="${current === 3 ? "current" : ""}"><b>3</b>查看订单</span></div>`;
export async function reservePage(q, offline = false) {
  if (!state.user) {
    return empty(
      "登录后继续预订",
      "查房结果已保留。登录后可以选择房间数量并核对费用。",
      button("login", "登录并继续", "", ""),
    );
  }
  if (offline ? !role("STAFF") : !role("CUSTOMER")) {
    return empty(
      "请使用顾客账号在线预订",
      "酒店前台请从“到店预订”入口办理线下订单。",
      `<a class="btn" href="#dashboard">返回工作台</a>`,
    );
  }
  let h, type;
  if (offline) {
    h = await api("/api/catalog/hotels/" + state.user.hotelId);
    type =
      h.roomTypes.find((r) => r.id === Number(q.get("type"))) || h.roomTypes[0];
  } else {
    ({ hotel: h, type } = await findType(q.get("type")));
  }
  const start = q.get("start") || state.today,
    end = q.get("end") || day(start, 1),
    guests = q.get("guests") || 1,
    rooms = q.get("rooms") || Math.ceil(Number(guests) / type.capacity);
  return `${heading(offline ? "到店预订" : q.get("modify") ? "选择新的预订方案" : "安排这次入住", offline ? "使用门市价结算。前台收款成功即确认并占用房量。" : "先选好日期与人数，下一步逐晚核对费用。")}${!offline ? steps(1) : ""}<div class="booking-layout"><form data-form="reserve" class="card stack">${hidden("offline", offline ? "true" : "false")}${q.has("modify") ? hidden("modify", q.get("modify")) : ""}${q.has("resume") ? hidden("resume", q.get("resume")) : ""}<h2>预订信息</h2><label>酒店<input value="${esc(h.name)} · ${esc(h.campus)}" readonly></label><label>房型<select name="roomTypeId" required>${h.roomTypes.map((r) => option(r.id, r.name + " · 每间最多" + r.capacity + "位", type.id)).join("")}</select></label><div class="grid2"><label>入住日期<input type="date" name="start" value="${esc(start)}" min="${state.today}" max="${day(state.today, 364)}" required></label><label>离店日期<input type="date" name="end" value="${esc(end)}" min="${day(start, 1)}" max="${day(state.today, 365)}" required></label><label>入住人数<input type="number" name="guests" value="${esc(guests)}" min="1" max="30" step="1" required><span class="form-note">支持1—30位，须填写整数。</span></label><label>预订房间数<input type="number" name="rooms" value="${esc(rooms)}" min="1" max="30" step="1" required><span class="form-note">每间至少安排1人，且不超过房型容量。</span></label></div>${offline ? '<label class="checkbox"><input type="checkbox" name="student">本校学生到店办理（已核验校园卡，门市价8折）</label>' : ""}${errorBox}<button class="btn" type="submit">核对费用与预订规则 →</button></form><aside class="card stack summary-sticky"><img class="summary-photo" src="${esc(type.image)}" alt="${esc(type.name)}"><div><h3>${esc(h.name)}</h3><p class="muted">${esc(type.name)}</p></div><hr class="divider">${notice("所有日期按北京时间计算。入住按住宿夜计费，不包含离店日；单次最多30晚。")}<a href="#guide" class="link-button">阅读完整预订须知 →</a></aside></div>`;
}
async function payPage(q) {
  if (!state.user) {
    return empty(
      "请先登录",
      "登录后重新核对本次预订费用。",
      button("login", "登录", "", ""),
    );
  }
  const offline = q.get("offline") === "true";
  const selection = {
    roomTypeId: Number(q.get("type")),
    start: q.get("start"),
    end: q.get("end"),
    guests: Number(q.get("guests")),
    rooms: Number(q.get("rooms")),
    offline,
    student: q.get("student") === "true",
  };
  const quote = await api("/api/quotes", selection);
  state.quote = quote;
  const { hotel, type } = await findType(selection.roomTypeId);
  const old = q.get("modify")
    ? await api("/api/bookings/" + Number(q.get("modify")))
    : null;
  state.order = old;
  return `${heading(old ? "核对修改方案" : "核对预订，安心入住", "金额由服务器按每个住宿夜计算；付款时会再次核对房价与余房。")}${steps(2)}<div class="booking-layout"><form data-form="pay" class="stack card">${hidden("quoteId", quote.id)}${hidden("offline", offline)}${q.has("resume") ? hidden("resume", q.get("resume")) : ""}${old ? hidden("modify", old.id) : ""}<h2>联系人信息</h2><div class="grid2"><label>联系人姓名<input name="contact" value="${esc(old?.contact || state.user.displayName)}" required maxlength="60" autocomplete="name"></label><label>联系电话<input name="phone" value="${esc(old?.phone || "")}" required pattern="[+0-9 \\-]{6,30}" maxlength="30" autocomplete="tel" inputmode="tel" placeholder="用于酒店联系与到店核验"></label></div><hr class="divider"><h3>取消与确认规则</h3>${old ? notice(`修改先取消原订单 #${old.id}，本次预计退还 ${money(old.refundable)}。原房不会保留；新订单付款失败或房量变化时，原订单也不会恢复。新订单付款与原订单退款分开记录。`, true) : notice(offline ? "到店订单按门市价结算，收款成功后直接确认。学生折扣仅适用于前台核验校园卡的线下预订。" : "付款成功后进入“待酒店确认”，尚未锁定房间。酒店按付款先后接单；30分钟未接单，或接单前已无可用房量时，自动取消并全额退款。")}<p class="technical-note">待酒店确认时取消，全额退款。已确认订单在入住日18:00之前取消，全额退款；18:00及之后取消不退款。已入住订单须由前台办理，不适用普通取消流程。</p>${offline && selection.student ? '<label class="checkbox"><input name="studentVerified" type="checkbox" required>已核验本校校园卡，确认使用门市价8折。</label>' : ""}<label class="checkbox"><input name="accepted" type="checkbox" required>我已核对日期、人数、房间和费用，并了解${old ? "原房不保留及新预订可能失败的修改风险" : "确认与取消规则"}。</label>${errorBox}<button type="submit" class="btn full">${old ? "确认修改并模拟付款" : offline ? "确认模拟收款并预订" : "确认模拟付款"} · ${money(quote.amount)}</button><small>课程实验支付，不会发生真实扣款。报价有效至 ${time(quote.expiresAt)}。</small></form><aside class="card stack summary-sticky"><img class="summary-photo" src="${esc(type.image)}" alt="${esc(type.name)}"><div><h3>${esc(hotel.name)}</h3><p class="muted">${esc(type.name)}</p></div>${keyvals(
    [
      ["入住 / 离店", `${selection.start} / ${selection.end}`],
      [
        "人数与房间",
        `${selection.guests}位 · ${selection.rooms}间 · ${quote.nights.length}晚`,
      ],
    ],
  )}<div class="stack tight">${quote.nights.map((n) => `<div class="price-line"><small>${n.day} × ${selection.rooms}间</small><span>${money(n.unitPrice * selection.rooms)}</span></div>`).join("")}</div><hr class="divider"><div class="price-line"><span>全程合计</span><strong class="money">${money(quote.amount)}</strong></div><a href="${href(offline ? "offline" : "reserve", Object.fromEntries(q))}" class="link-button">返回调整预订信息</a></aside></div>`;
}
export async function ordersPage(q) {
  if (!state.user) {
    return empty(
      "登录后查看你的订单",
      "预订进度、退款和入住记录都保存在账号中。",
      button("login", "登录查看订单", "", ""),
    );
  }
  const data = await api("/api/bookings?" + q);
  const values = Object.fromEntries(q);
  return `${heading(role("CUSTOMER") ? "我的订单" : "订单接待与查询", role("CUSTOMER") ? "查看预订进度，管理尚未入住的行程。" : "先查看订单当前状态，再办理接单、入住或离店。", role("STAFF") ? '<a class="btn" href="#offline">＋ 到店预订</a>' : "")}<form class="card filter" data-form="orders"><label>订单状态<select name="status">${option("", "全部状态", values.status)}${["PENDING", "CONFIRMED", "CHECKED_IN", "CHECKED_OUT", "CANCELLED", "NO_SHOW"].map((s) => option(s, label(s), values.status)).join("")}</select></label><label>联系人<input name="contact" maxlength="60" value="${esc(values.contact || "")}" placeholder="按姓名搜索"></label><label>日期类型<select name="dateType">${[
    ["check_in", "入住日期"],
    ["check_out", "离店日期"],
    ["created_at", "下单日期"],
    ["paid_at", "付款日期"],
  ]
    .map(([v, t]) => option(v, t, values.dateType))
    .join(
      "",
    )}</select></label><label>开始日期<input type="date" name="from" value="${esc(values.from || "")}"></label><label>结束日期（含）<input type="date" name="to" value="${esc(values.to || "")}"></label><button class="btn" type="submit">查询订单</button>${errorBox}</form><div class="section-heading"><p class="muted">共 ${data.total} 笔订单</p>${button("refresh", "刷新列表")}</div>${
    data.items.length
      ? table(
          [
            "订单 / 酒店",
            "联系人",
            "入住行程",
            "人数 / 房间",
            "实付房费",
            "状态",
            "操作",
          ],
          data.items.map(
            (b) =>
              `<tr><td><a href="${href("order", { id: b.id })}">#${b.id} · ${esc(b.hotel_name)}</a><br><small>${esc(b.room_type_name)} · ${b.channel === "ONLINE" ? "线上" : "到店"}</small></td><td>${esc(b.contact)}</td><td>${b.check_in} → ${b.check_out}</td><td>${b.guest_count}位 / ${b.rooms}间</td><td>${money(b.amount)}</td><td>${badge(b.status)}</td><td><a href="${href("order", { id: b.id })}">查看${role("STAFF") ? " / 办理" : "详情"} →</a></td></tr>`,
          ),
        )
      : empty(
          "这里还没有匹配的订单",
          "可调整筛选条件，或先查询酒店空房。",
          role("CUSTOMER") ? '<a class="btn" href="#home">去查房</a>' : "",
        )
  }<div class="pagination"><span>第 ${data.page} / ${Math.max(1, Math.ceil(data.total / 30))} 页 · 每页最多30条</span><div class="actions">${data.page > 1 ? `<a class="btn secondary small" href="${href("orders", { ...values, page: data.page - 1 })}">上一页</a>` : ""}${data.page * 30 < data.total ? `<a class="btn secondary small" href="${href("orders", { ...values, page: data.page + 1 })}">下一页</a>` : ""}</div></div>`;
}
export async function orderPage(q) {
  if (!state.user) {
    return empty(
      "请先登录",
      "登录后可以查看订单详情。",
      button("login", "登录", "", ""),
    );
  }
  const b = await api("/api/bookings/" + Number(q.get("id")));
  state.order = b;
  let actions = "";
  if (role("CUSTOMER") && ["PENDING", "CONFIRMED"].includes(b.status)) {
    actions = `${button("cancel-order", "取消预订", b.id)}<a class="btn secondary" href="${href("reserve", { type: b.room_type_id, start: b.check_in < state.today ? state.today : b.check_in, end: b.check_out > state.today ? b.check_out : day(state.today, 1), guests: b.guest_count, rooms: b.rooms, modify: b.id })}">修改预订</a>`;
  }
  if (role("STAFF")) {
    if (b.status === "PENDING") {
      actions = button("confirm-order", "确认接单", b.id, "");
    }
    if (b.status === "CONFIRMED") {
      actions = `<a class="btn" href="${href("checkin", { id: b.id })}">办理入住</a>`;
    }
    if (b.status === "CHECKED_IN") {
      actions = `<a class="btn" href="${href("checkout", { id: b.id })}">办理退房</a><a class="btn secondary" href="${href("extend", { id: b.id })}">办理续住</a>${button("reception-request", "登记接待事项", b.id)}${button("guest-records", "查看住客登记", b.id)}`;
    }
  }
  const statusInfo = {
    PENDING: `付款已记录，酒店确认后才保留房间。接单截止时间：${time(b.deadline)}。`,
    CONFIRMED:
      "酒店已确认并为整个行程保留房量。请到店后由前台核验身份、安排具体房间。",
    CHECKED_IN: "已办理入住。续住或其他接待事项请联系前台。",
    CANCELLED: `此订单已取消。${b.reason === "MODIFICATION" ? "原单修改后已释放房量，新的预订单独记录。" : "退款情况请查看下方资金记录。"}`,
    NO_SHOW: "到原定离店日12:00仍未办理入住，订单已结束，不退款。",
    CHECKED_OUT: "此行程已结束，感谢入住。",
  }[b.status];
  return `<div class="breadcrumbs"><a href="#orders">订单列表</a><span>/</span><span>#${b.id}</span></div>${heading(`订单 #${b.id}`, `${b.hotelName} · ${b.roomTypeName}`, badge(b.status))}<div class="booking-layout"><div class="stack"><section class="card stack">${notice(statusInfo, b.status === "PENDING")}<h2>行程与联系人</h2>${keyvals(
    [
      ["入住日期", b.check_in],
      ["离店日期", b.check_out + " 12:00前"],
      ["住客与房间", `${b.guest_count}位 · ${b.rooms}间`],
      ["订单渠道", b.channel === "ONLINE" ? "线上预订" : "到店办理"],
      ["联系人", b.contact],
      ["联系电话", b.phone],
      ["付款时间", time(b.paid_at)],
      [
        "具体房间",
        b.stays.length
          ? b.stays.map((s) => s.number).join("、")
          : "到店时由前台安排",
      ],
    ],
  )}<div class="actions">${actions}${button("refresh", "刷新状态")}</div></section><section class="card stack"><h3>逐晚费用</h3>${table(
    ["住宿日期", "每间单价", "房间数", "小计"],
    b.nights.map(
      (n) =>
        `<tr><td>${n.stay_date}</td><td>${money(n.unit_price)}</td><td>${n.rooms}</td><td>${money(n.unit_price * n.rooms)}</td></tr>`,
    ),
  )}</section><section class="card stack"><h3>资金记录 <small>模拟收退款</small></h3>${table(
    ["类型", "金额", "发生时间", "说明"],
    b.payments.map(
      (p) =>
        `<tr><td>${esc(label(p.kind))}</td><td>${p.kind === "REFUND" ? "−" : ""}${money(p.amount)}</td><td>${time(p.created_at)}</td><td>${esc(reason(p.reason))}</td></tr>`,
    ),
  )}</section>${b.modifications.length ? `<section class="card stack"><h3>预订修改记录</h3>${b.modifications.map((m) => `<div class="notice"><p>${esc(label(m.state))} · 原单 #${m.old_booking_id}${m.new_booking_id ? ` → <a href="${href("order", { id: m.new_booking_id })}">新单 #${m.new_booking_id}</a>` : ""}</p>${!m.new_booking_id && role("CUSTOMER") ? `<p>新单尚未生成，原单已取消；可重新查房获得新报价，再继续该次修改。</p><a class="link-button" href="${href("resume", { id: m.id, type: b.room_type_id })}">继续完成新预订 →</a>` : ""}</div>`).join("")}</section>` : ""}${b.requests.length ? `<section class="card stack"><h3>接待事项</h3>${b.requests.map((r) => `<div class="notice"><p>${esc(label(r.kind))} · ${badge(r.status)}</p><p>${esc(r.reason)}</p>${r.resolution ? `<p>处理结论：${esc(r.resolution)}</p>` : role("STAFF") ? button("resolve-request", "记录处理结论", r.id) : ""}</div>`).join("")}</section>` : ""}</div><aside class="card stack summary-sticky"><h3>订单费用</h3><div class="price-line"><span>住宿实付</span><strong class="money">${money(b.amount)}</strong></div><div class="price-line"><span>已退金额</span><span>${money(b.payments.filter((p) => p.kind === "REFUND").reduce((sum, p) => sum + p.amount, 0))}</span></div><hr class="divider">${["PENDING", "CONFIRMED"].includes(b.status) ? `<div class="price-line"><span>现在取消预计退还</span><strong>${money(b.refundable)}</strong></div>` : ""}<p class="technical-note">退款以提交取消时的北京时间为准。已确认订单：入住日18:00之前全退，18:00起不退。操作前会再次展示确认金额。</p><a href="#guide" class="link-button">查看预订规则 →</a></aside></div>`;
}
function reason(value) {
  if (value?.startsWith("INCIDENT_")) {
    return "酒店应急全额退款";
  }
  if (value?.startsWith("TO_")) {
    return "续住至 " + value.slice(3);
  }
  return (
    {
      INITIAL: "初次预订",
      CUSTOMER_CANCEL: "顾客取消",
      MODIFICATION: "修改原订单",
      TIMEOUT: "30分钟接单超时",
      NO_AVAILABILITY: "无房可接",
      CHECKOUT: "退房超时费用",
    }[value] || value
  );
}
function guidePage() {
  return `${heading("预订须知", "付款前看清安排，发生变化时知道下一步怎么办。")}<div class="stack"><section class="card stack"><h2>从查房到入住</h2><p>选择入住和离店日期，填写1—30位的整数人数。系统建议房间数量，也可自行调整；每间至少安排1人且不能超过房型容量。单次预订为1—30晚，日期采用北京时间，离店日不计入住宿夜。</p><p>线上模拟付款成功后进入“待酒店确认”，此时尚未占用房量。前台接单时重新检查每一晚余房，并按先付款、先处理的原则确认。待接单超过30分钟或已无房可接时，订单自动取消并全额退款。</p><p>酒店确认后，整个行程房量得到保留，具体房号在到店时安排。全部住客须接受身份核验；为他人代订时，前台须电话联系下单人核验。</p></section><section class="card stack"><h2>取消与修改</h2>${table(["当前情况", "取消结果"], ["<tr><td>待酒店确认</td><td>全额退款</td></tr>", "<tr><td>已确认，入住日18:00之前</td><td>全额退款</td></tr>", "<tr><td>已确认，入住日18:00及之后</td><td>取消并释放房量，不退款</td></tr>", "<tr><td>已办理入住</td><td>联系前台办理，不走普通取消流程</td></tr>"])}<p>修改预订会先取消原单，再按最新报价新建订单。原房不会保留，即使新付款失败，原单也不会恢复；可从原单修改记录继续办理新预订。修改限同一酒店，具体退款金额在确认前展示。</p><p>已确认但未到店的订单保留全部原定住宿夜，直到原定离店日12:00才结束，不因入住日过期而提前释放后续房量。</p></section><section class="card stack"><h2>到店服务与费用</h2><p>线下预订使用门市价；本校学生经前台核验校园卡后按门市价8折结算，线上不叠加学生优惠。续住需在原退房期限前办理，并重新检查新增住宿夜的房价与余房。</p><p>正常退房时间为原定离店日12:00。超时费用按入住时确定的每订单小时费率计算，不足1小时按1小时计；办理退房前由前台展示并确认。退房后的房间先进入待清洁状态，清洁完成后再安排新客人。</p><p>提前退房、超期续住、在住补偿等未统一约定的特殊事项，由前台登记并人工处理，不自动套用普通退款规则。</p></section>${notice("本系统为软件工程课程实验。酒店信息为演示数据，支付、退款和身份核验均为流程模拟；请勿输入真实证件号码。")}</div>`;
}
let workModule;
async function work() {
  return (workModule ||= await import("/assets/work.js"));
}
let generation = 0;
export async function render() {
  const current = ++generation;
  const [route = "home", query = ""] = (location.hash.slice(1) || "home").split(
    "?",
  );
  state.route = route;
  state.query = new URLSearchParams(query);
  shell();
  $("#main").innerHTML = '<div class="loading">正在读取最新数据…</div>';
  try {
    let html;
    switch (route) {
      case "home":
        html = await homePage();
        break;
      case "search":
        html = await searchPage(state.query);
        break;
      case "hotel":
        html = await hotelPage(state.query);
        break;
      case "reserve":
        html = await reservePage(state.query);
        break;
      case "pay":
        html = await payPage(state.query);
        break;
      case "orders":
        html = await ordersPage(state.query);
        break;
      case "order":
        html = await orderPage(state.query);
        break;
      case "guide":
        html = guidePage();
        break;
      default:
        html = await (await work()).page(route, state.query);
    }
    if (current !== generation) {
      return;
    }
    $("#main").innerHTML = html;
    document.title = `${$("#main h1")?.textContent || "校内酒店预订"} · 校舍`;
  } catch (error) {
    if (current !== generation) {
      return;
    }
    $("#main").innerHTML = empty(
      "暂时无法显示这项内容",
      error.message,
      `${button("refresh", "重新加载", "", "")}<a class="btn secondary" href="#home">返回首页</a>`,
    );
  }
}
async function handleForm(form) {
  const data = fields(form);
  switch (form.dataset.form) {
    case "login": {
      const result = await api("/api/auth/login", data);
      state.user = result.user;
      state.csrf = null;
      closeModal();
      if (role("STAFF") || role("ADMIN")) {
        go("dashboard");
      } else {
        await render();
      }
      toast("登录成功");
      break;
    }
    case "register":
      await api("/api/auth/register", data);
      loginDialog();
      toast("注册成功，请登录");
      break;
    case "search": {
      if (data.budgetYuan) {
        data.budget = String(Math.round(Number(data.budgetYuan) * 100));
      }
      delete data.budgetYuan;
      go("search", data);
      break;
    }
    case "orders":
      if (data.from && data.to && data.from > data.to) {
        throw new Error("开始日期不能晚于结束日期。");
      }
      go(
        "orders",
        Object.fromEntries(Object.entries(data).filter(([, v]) => v)),
      );
      break;
    case "reserve":
      go("pay", {
        ...data,
        type: data.roomTypeId,
        student: checked(form, "student"),
      });
      break;
    case "pay": {
      const body = {
        quoteId: data.quoteId,
        contact: data.contact,
        phone: data.phone,
        accepted: checked(form, "accepted"),
        studentVerified: checked(form, "studentVerified"),
      };
      let result;
      if (data.resume) {
        result = await mutation(
          `/api/modifications/${Number(data.resume)}/complete`,
          body,
          form,
        );
      } else if (data.modify) {
        let modification = form.dataset.modification;
        if (!modification) {
          const old = state.order;
          const begun = await mutation(
            `/api/bookings/${old.id}/modify`,
            {
              quoteId: data.quoteId,
              version: old.version,
              expectedRefund: old.refundable,
              riskAccepted: body.accepted,
            },
            form,
          );
          modification = String(begun.id);
          form.dataset.modification = modification;
          form.dataset.key = "";
          form.dataset.payload = "";
        }
        try {
          result = await mutation(
            `/api/modifications/${modification}/complete`,
            { ...body, quoteId: data.quoteId },
            form,
          );
        } catch (error) {
          throw new Error(
            "原订单已取消，新订单尚未完成。" +
              error.message +
              "可从原订单的修改记录继续办理。",
          );
        }
      } else {
        result = await mutation("/api/bookings", body, form);
      }
      go("order", { id: result.id });
      toast("模拟付款成功，订单已保存");
      break;
    }
    case "cancel-order": {
      const b = state.order;
      await mutation(
        `/api/bookings/${b.id}/cancel`,
        { version: b.version, expectedRefund: b.refundable },
        form,
      );
      closeModal();
      await render();
      toast("订单已取消，退款结果已记录");
      break;
    }
    default:
      await (await work()).form(form, data);
  }
}
document.addEventListener("submit", async (event) => {
  const form = event.target;
  if (!form.matches("form[data-form]")) {
    return;
  }
  event.preventDefault();
  if (form.dataset.busy === "true") {
    return;
  }
  const error = $(".form-error", form);
  if (error) {
    error.textContent = "";
  }
  const buttons = $$('button[type="submit"]', form);
  form.dataset.busy = "true";
  buttons.forEach((b) => (b.disabled = true));
  try {
    await handleForm(form);
  } catch (failure) {
    if (error && form.isConnected) {
      error.textContent = failure.message;
      error.scrollIntoView({ block: "nearest", behavior: "smooth" });
    } else {
      toast(failure.message);
    }
  } finally {
    form.dataset.busy = "false";
    buttons.forEach((b) => (b.disabled = false));
  }
});
document.addEventListener("click", async (event) => {
  const target = event.target.closest("[data-action]");
  if (!target || target.disabled) {
    return;
  }
  const action = target.dataset.action;
  try {
    switch (action) {
      case "login":
        loginDialog();
        break;
      case "register":
        registerDialog();
        break;
      case "close":
        closeModal();
        break;
      case "logout":
        await api("/api/auth/logout", {});
        state.user = null;
        state.csrf = null;
        state.quote = null;
        state.order = null;
        go("home");
        break;
      case "refresh":
        if (!state.today) {
          await init();
        } else {
          await render();
        }
        break;
      case "cancel-order": {
        const b = await api("/api/bookings/" + Number(target.dataset.id));
        state.order = b;
        modal(
          "确认取消预订",
          `<form data-form="cancel-order" class="stack"><p>将取消 ${esc(b.hotelName)} 的订单 #${b.id}，已保留房量会释放。</p><div class="price-line"><span>本次预计退还</span><strong class="money">${money(b.refundable)}</strong></div>${notice("退款以提交时为准。如刚好跨过入住日18:00，系统会要求重新确认金额。", true)}${errorBox}<div class="actions end">${button("close", "保留预订")}<button type="submit" class="btn danger">确认取消</button></div></form>`,
        );
        break;
      }
      default:
        target.disabled = true;
        await (await work()).action(action, target.dataset.id, target);
    }
  } catch (error) {
    toast(error.message);
  } finally {
    target.disabled = false;
  }
});
document.addEventListener("change", async (event) => {
  const element = event.target;
  if (element.name === "start" && element.form?.elements.namedItem("end")) {
    const end = element.form.elements.namedItem("end");
    if (element.value) {
      end.min = day(element.value, 1);
      if (end.value <= element.value) {
        end.value = day(element.value, 1);
      }
    }
  }
  if (element.dataset.change) {
    try {
      await (await work()).change(element);
    } catch (error) {
      toast(error.message);
    }
  }
});
window.addEventListener("hashchange", () => {
  closeModal();
  render();
  window.scrollTo({ top: 0 });
});
async function init() {
  try {
    const [me, hotels] = await Promise.all([
      api("/api/auth/me"),
      api("/api/catalog/hotels"),
    ]);
    state.user = me.user || null;
    state.today = me.today;
    state.hotels = hotels;
    await render();
  } catch (error) {
    $("#main").innerHTML = empty(
      "暂时无法连接酒店服务",
      error.message,
      '<button class="btn" type="button" data-action="refresh">重新连接</button>',
    );
  }
}
init();
