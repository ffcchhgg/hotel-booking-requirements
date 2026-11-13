import {
  $,
  $$,
  state,
  esc,
  money,
  day,
  time,
  role,
  label,
  badge,
  href,
  go,
  fields,
  checked,
  hidden,
  option,
  errorBox,
  button,
  heading,
  empty,
  table,
  keyvals,
  notice,
  toast,
  api,
  mutation,
  modal,
  closeModal,
  render,
  reservePage,
} from "/app.js";

function hotelId(q = state.query) {
  return role("STAFF")
    ? state.user.hotelId
    : Number(q.get("hotel") || state.hotels[0].id);
}
function hotelFilter(route, q, dates = false) {
  return `<form class="card filter" data-form="view-filter">${hidden("route", route)}<label>酒店<select name="hotel">${(role("STAFF") ? state.hotels.filter((h) => h.id === state.user.hotelId) : state.hotels).map((h) => option(h.id, h.name + " · " + h.campus, hotelId(q))).join("")}</select></label>${dates ? `<label>开始日期<input name="start" type="date" value="${esc(q.get("start") || state.today)}" required></label><label>结束日期（不含）<input name="end" type="date" value="${esc(q.get("end") || day(state.today, route === "statistics" ? 1 : 7))}" required></label>` : ""}<button class="btn" type="submit">查看</button>${errorBox}</form>`;
}
function linkOrder(b) {
  return `<a href="${href("order", { id: b.id })}">#${b.id}</a>`;
}
function receptionRows(items) {
  return table(
    ["订单", "联系人 / 房型", "入住 → 离店", "房间", "状态", "操作"],
    items.map(
      (b) =>
        `<tr><td>${linkOrder(b)}</td><td>${esc(b.contact)}<br><small>${esc(b.room_type_name)}</small></td><td>${b.check_in} → ${b.check_out}</td><td>${b.rooms}间</td><td>${badge(b.status)}</td><td><a href="${href("order", { id: b.id })}">查看 / 办理 →</a></td></tr>`,
    ),
  );
}
async function dashboard(q) {
  if (role("ADMIN")) {
    return statistics(q, true);
  }
  const [pending, arriving, staying, roomData] = await Promise.all([
    api("/api/bookings?status=PENDING"),
    api(
      "/api/bookings?" +
        new URLSearchParams({
          status: "CONFIRMED",
          from: state.today,
          to: state.today,
        }),
    ),
    api("/api/bookings?status=CHECKED_IN"),
    api("/api/reception/rooms"),
  ]);
  const dirty = roomData.items.filter((r) => r.state === "DIRTY").length;
  const hotel = state.hotels.find((h) => h.id === state.user.hotelId);
  const metrics = [
    [
      "待接订单",
      pending.total,
      "付款后30分钟内处理",
      "orders",
      { status: "PENDING" },
    ],
    [
      "今日待入住",
      arriving.total,
      "完成身份核验后分房",
      "orders",
      { status: "CONFIRMED", from: state.today, to: state.today },
    ],
    [
      "在住订单",
      staying.total,
      "查看续住与离店安排",
      "orders",
      { status: "CHECKED_IN" },
    ],
    ["待清洁客房", dirty, "清洁确认后才可安排", "rooms", {}],
  ];
  return `${heading("前台工作概览", `${hotel.name} · ${state.today} · 北京时间`, '<a class="btn" href="#offline">＋ 到店预订</a>')}<div class="grid4">${metrics.map(([name, value, note, route, params]) => `<a class="metric" href="${href(route, params)}"><div class="metric-label">${name}</div><div class="value">${value}<small> ${name === "待清洁客房" ? "间" : "笔"}</small></div><small>${note} →</small></a>`).join("")}</div><section class="stack"><div class="section-heading"><h2>优先处理待接订单</h2><a href="${href("orders", { status: "PENDING" })}">查看全部 →</a></div>${pending.items.length ? receptionRows([...pending.items].reverse().slice(0, 8)) : empty("当前没有待接订单", "新订单付款后会出现在这里。可点击刷新查看最新状态。", button("refresh", "刷新工作台"))}</section><section class="stack"><div class="section-heading"><h2>今日预计到店</h2><a href="${href("orders", { status: "CONFIRMED", from: state.today, to: state.today })}">查看全部 →</a></div>${arriving.items.length ? receptionRows(arriving.items.slice(0, 8)) : notice("今天暂时没有待办理入住的订单。")}</section>${notice("在线订单按付款先后处理；待接订单不占房。线下预订、续住或资料审核改变房量后，服务器会重新检查待接订单。")}`;
}
async function roomsPage() {
  const data = await api("/api/reception/rooms");
  return `${heading("客房与清洁", "房间状态来自实际入住与退房记录；待清洁房间不能直接安排新住客。", button("refresh", "刷新房态"))}<div class="tabs">${["READY", "OCCUPIED", "DIRTY"].map((s) => `<span class="tab">${esc(label(s))} ${data.items.filter((r) => r.state === s).length}间</span>`).join("")}</div><div class="room-grid">${data.items.map((r) => `<article class="room-tile ${r.state.toLowerCase()}">${badge(r.state)}<strong>${esc(r.number)}</strong><p>${esc(r.room_type_name)} · 最多${r.capacity}位</p>${r.state === "DIRTY" ? button("clean", "确认清洁完成", r.id, "secondary small") : r.booking_id ? `<a href="${href("order", { id: r.booking_id })}">查看在住订单 #${r.booking_id} →</a>` : "<small>等待安排入住</small>"}</article>`).join("")}</div>`;
}
async function checkinPage(q) {
  const id = Number(q.get("id"));
  const [order, rooms] = await Promise.all([
    api("/api/bookings/" + id),
    api("/api/reception/rooms"),
  ]);
  state.order = order;
  if (order.status !== "CONFIRMED") {
    throw new Error("当前订单不是待入住状态，请返回订单查看最新状态。");
  }
  const suitable = rooms.items.filter(
    (r) => r.state === "READY" && r.room_type_id === order.room_type_id,
  );
  if (suitable.length < order.rooms) {
    return empty(
      "已清洁可安排的房间不足",
      "请先检查其他客房是否尚未退房或清洁，不能强行入住。",
      '<a class="btn" href="#rooms">查看客房状态</a>',
    );
  }
  const type = (
    await api("/api/catalog/hotels/" + order.hotel_id)
  ).roomTypes.find((r) => r.id === order.room_type_id);
  const assignGuest = (index) => index % order.rooms;
  return `${heading("办理入住", `订单 #${id} · ${order.hotelName} · ${order.roomTypeName}`)}<form data-form="checkin" class="stack card">${keyvals(
    [
      ["预订行程", `${order.check_in} → ${order.check_out}`],
      ["登记人数", `${order.guest_count}位 / ${order.rooms}间`],
      ["联系人", order.contact],
      ["联系电话", order.phone],
    ],
  )}<hr class="divider"><h3>1. 安排已清洁房间</h3><div class="grid3">${Array.from({ length: order.rooms }, (_, index) => `<label>第${index + 1}间房<select name="room_${index}" required>${suitable.map((r, n) => option(r.id, r.number + " · " + r.room_type_name, suitable[index]?.id)).join("")}</select></label>`).join("")}</div><h3>2. 登记全部住客</h3>${notice("仅使用虚构测试姓名和证件号码。提交后证件号仅脱敏显示，不进入操作日志。")}<div class="stack">${Array.from({ length: order.guest_count }, (_, index) => `<fieldset><legend>住客 ${index + 1}</legend><div class="guest-row"><label>姓名<input name="guest_name_${index}" required maxlength="60" autocomplete="off"></label><label>联系电话<input name="guest_phone_${index}" required pattern="[+0-9 \\-]{6,30}" maxlength="30" autocomplete="off"></label><label>模拟证件号码<input name="guest_document_${index}" required pattern="[a-zA-Z0-9\\-]{6,40}" maxlength="40" autocomplete="off" placeholder="例如 TEST000001"></label><label>安排至<select name="guest_room_${index}">${Array.from({ length: order.rooms }, (_, n) => option(n, `第${n + 1}间`, assignGuest(index))).join("")}</select></label></div></fieldset>`).join("")}</div><label class="checkbox"><input type="checkbox" name="identityVerified" required>已完成全部住客的模拟身份核验。</label><label class="checkbox"><input type="checkbox" name="proxyBooking" data-change="proxy">这是为他人代订的订单。</label><label class="checkbox" id="proxy-check" hidden><input type="checkbox" name="phoneVerified">已电话联系下单人并完成核验。</label>${errorBox}<div class="actions end"><a class="btn secondary" href="${href("order", { id })}">返回订单</a><button type="submit" class="btn">完成登记并入住</button></div></form>`;
}
async function checkoutPage(q) {
  const id = Number(q.get("id"));
  const [order, preview] = await Promise.all([
    api("/api/bookings/" + id),
    api(`/api/reception/bookings/${id}/checkout`),
  ]);
  state.order = order;
  state.checkout = preview;
  if (preview.early) {
    return `${heading("当前属于提前退房", `订单 #${id} 的原定离店日为 ${order.check_out}`)}${notice("提前退房的退款政策尚未统一确定。请登记接待事项，记录人工沟通与处理结果；此入口不自动取消已入住订单或退款。", true)}<div class="actions">${button("reception-request", "登记提前退房事项", id, "")}<a class="btn secondary" href="${href("order", { id })}">返回订单</a></div>`;
  }
  return `${heading("办理退房", `订单 #${id} · ${order.contact} · ${order.hotelName}`)}<div class="booking-layout"><form class="card stack" data-form="checkout"><h2>核对离店费用</h2>${keyvals(
    [
      ["原定离店时间", order.check_out + " 12:00"],
      ["入住时小时费率", money(preview.hourlyRate) + " / 订单"],
      ["办理房间", order.stays.map((s) => s.number).join("、")],
      ["当前预计超时费", money(preview.fee)],
    ],
  )}${notice(preview.rule)}<label class="checkbox"><input name="paymentConfirmed" type="checkbox" required>已核对费用并完成模拟收款（无费用时确认无需补款）。</label>${errorBox}<button class="btn" type="submit">确认退房${preview.fee ? " · 收取 " + money(preview.fee) : ""}</button><a href="${href("order", { id })}" class="link-button">返回订单</a></form><aside class="card stack"><h3>离店后的安排</h3><p class="muted">退房成功后，所住客房会进入“待清洁”。完成清洁确认后，才能安排给下一位住客。</p><p class="technical-note">提交时服务器再次计算超时费。如果跨过新的计费小时，将要求刷新并重新确认金额。</p></aside></div>`;
}
async function extendPage(q) {
  const id = Number(q.get("id"));
  const order = await api("/api/bookings/" + id);
  state.order = order;
  if (order.status !== "CHECKED_IN") {
    throw new Error("仅在住订单可办理续住。");
  }
  return `${heading("办理续住", `订单 #${id} · ${order.roomTypeName} · ${order.rooms}间`)}<form data-form="extend-quote" class="card stack"><h3>选择新的离店日期</h3>${keyvals(
    [
      ["当前入住日", order.check_in],
      ["原定离店日", order.check_out],
    ],
  )}<label>新离店日期<input name="end" type="date" value="${day(order.check_out, 1)}" min="${day(order.check_out, 1)}" max="${day(order.check_in, 30)}" required></label>${notice("续住只计算新增住宿夜的费用。新增夜逐晚核验余房，原住宿费用不会重算；须在原定离店日12:00之前办理。")}${errorBox}<div class="actions"><button type="submit" class="btn">查询续住费用</button><a class="btn secondary" href="${href("order", { id })}">返回订单</a></div></form><div id="extension-result"></div>`;
}
async function managePage(q) {
  const hotel = hotelId(q),
    start = q.get("start") || state.today,
    end = q.get("end") || day(start, 7);
  const data = await api(
    "/api/manage/catalog?" +
      new URLSearchParams({ hotelId: hotel, start, end }),
  );
  state.manage = data;
  return `${heading("酒店资料与房量", role("STAFF") ? "修改先形成申请，经管理员审核后对顾客生效。" : "查看已生效资料，并设置新入住订单使用的超时费率。")}${hotelFilter("manage", q, true)}<div class="card stack"><div class="section-heading"><div><h2>${esc(data.hotel.name)}</h2><p class="muted">${esc(data.hotel.campus)} · 当前资料版本 ${data.hotel.version}</p></div>${role("STAFF") ? button("edit-info", "修改酒店资料", "hotel") : ""}</div><p>${esc(data.hotel.description)}</p><div class="grid3">${data.types.map((t) => `<div class="notice"><h4>${esc(t.name)}</h4><p>每间最多${t.capacity}位</p><p>${esc(t.description)}</p>${role("STAFF") ? button("edit-info", "修改房型资料", t.id, "secondary small") : ""}</div>`).join("")}</div></div><div class="section-heading"><div><h2>房价与每日房量</h2><p class="muted">房价以元展示，房量按住宿夜管理。</p></div>${role("STAFF") ? button("inventory-edit", "提交房价 / 房量调整", "", "") : ""}</div>${table(
    ["日期", "房型", "门市价", "线上价", "总房量", "已占用", "销售状态"],
    data.inventory.map(
      (i) =>
        `<tr><td>${i.stay_date}</td><td>${esc(i.room_type_name)}</td><td>${money(i.public_price)}</td><td>${money(i.online_price)}</td><td>${i.capacity}</td><td>${i.used}</td><td>${i.emergency_stop ? badge("MANUAL") + " 应急停售" : i.stop_sale ? '<span class="badge closed">常规停售</span>' : '<span class="badge">销售中</span>'}</td></tr>`,
    ),
  )}<section class="card stack"><div class="price-line"><h3>超时退房费率</h3>${role("ADMIN") ? button("fee-edit", "设置新费率", hotel) : ""}</div><p>当前新入住费率：<strong>${data.feePolicies.length ? money(data.feePolicies[0].hourly_cents) + " / 每订单每小时" : "尚未配置"}</strong></p><p class="technical-note">入住时保存费率快照，后续调整不会改变已经在住订单的计费标准。计时从原定离店日12:00开始，不足1小时按1小时。</p></section>`;
}
async function changesPage(q) {
  const data = await api("/api/manage/changes?hotelId=" + hotelId(q));
  const rows = data.items;
  return `${heading(role("STAFF") ? "资料申请记录" : "酒店资料审核", role("STAFF") ? "跟踪每次提交的内容、审核结果与意见。" : "核对提交内容后批准或驳回，过期版本不能覆盖更新后的资料。")}${hotelFilter("changes", q)}<div class="stack">${rows.length ? rows.map((c) => `<article class="card review-item"><div class="price-line"><h3>#${c.id} · ${esc(label(c.kind))}</h3>${badge(c.status)}</div><p class="technical-note">${esc(c.author_name)} 提交于 ${time(c.created_at)}</p>${keyvals(Object.entries(c.payload).map(([k, v]) => [{ name: "名称", description: "介绍", image: "图片资源", start: "开始日期", end: "结束日期（不含）", capacity: "每日总房量", publicPrice: "门市价", onlinePrice: "线上价", stopSale: "常规停售" }[k] || k, ["publicPrice", "onlinePrice"].includes(k) ? money(v) : typeof v === "boolean" ? (v ? "是" : "否") : v]))}${c.payload.image ? `<img src="${esc(c.payload.image)}" alt="待审核图片" class="photo-preview">` : ""}${c.note ? notice("审核意见：" + c.note) : ""}${role("ADMIN") && c.status === "PENDING" ? `<div class="actions">${button("review-approve", "批准并发布", c.id, "")}${button("review-reject", "驳回申请", c.id)}</div>` : ""}</article>`).join("") : empty("暂无资料申请", role("STAFF") ? "可从“资料与房量”提交需要调整的内容。" : "前台提交申请后会在这里显示。")}</div>`;
}
async function incidentPage(q) {
  const hotel = hotelId(q);
  const [data, info] = await Promise.all([
    api("/api/manage/incidents?hotelId=" + hotel),
    api("/api/catalog/hotels/" + hotel),
  ]);
  state.incidentTypes = info.roomTypes;
  return `${heading(role("STAFF") ? "应急处理" : "应急事件记录", "先阻止新增预订，再处理受影响订单并记录结果。", role("STAFF") ? button("incident-new", "登记突发事件", "", "danger") : "")}${hotelFilter("incidents", q)}${notice("未入住订单因酒店原因受影响时全额退款，不受18:00限制；已入住订单保留在住状态，列入人工处理事项。在住事项全部记录结论后，前台才能确认恢复接待。", true)}<div class="stack">${
    data.items.length
      ? data.items
          .map(
            (i) =>
              `<section class="card stack"><div class="price-line"><h3>事件 #${i.id} · ${esc(i.room_type_name)}</h3>${badge(i.status)}</div><p>${esc(i.reason)}</p><p class="technical-note">影响住宿夜：${i.start_date} 至 ${i.end_date}（结束日不含） · 登记于 ${time(i.created_at)}</p>${
                i.cases.length
                  ? table(
                      ["受影响订单", "联系人", "处理状态", "结论 / 操作"],
                      i.cases.map(
                        (c) =>
                          `<tr><td><a href="${href("order", { id: c.booking_id })}">#${c.booking_id}</a></td><td>${esc(c.contact)}</td><td>${badge(c.state)}</td><td class="wrap">${esc(c.note)}${role("STAFF") && c.state === "MANUAL" ? button("incident-resolve", "记录处理结论", `${i.id}:${c.booking_id}`, "secondary small") : ""}</td></tr>`,
                      ),
                    )
                  : notice("登记时没有受影响的有效订单。")
              }${role("STAFF") && i.status === "OPEN" ? `<div class="actions end">${button("incident-close", "确认恢复接待", i.id)}</div>` : ""}</section>`,
          )
          .join("")
      : empty(
          "暂无应急事件",
          "发生接待中断时，由所属酒店前台登记影响日期和房型。",
        )
  }</div>`;
}
async function statistics(q, overview = false) {
  const hotel = hotelId(q),
    start = q.get("start") || state.today,
    end = q.get("end") || day(state.today, 1);
  const data = await api(
    "/api/manage/statistics?" +
      new URLSearchParams({ hotelId: hotel, start, end }),
  );
  const roomNights = data.daily.reduce((n, d) => n + Number(d.occupied), 0),
    capacity = data.daily.reduce((n, d) => n + Number(d.capacity), 0);
  const count = data.orders.reduce((n, d) => n + Number(d.count), 0);
  return `${heading(overview ? "经营与管理概览" : "经营统计", "按酒店与北京时间日期汇总实际记录，结束日期不包含在内。")}${hotelFilter(overview ? "dashboard" : "statistics", q, true)}<div class="grid4">${[
    ["收款总额", money(data.money.gross), "含预订、续住与超时费用"],
    ["退款总额", money(data.money.refunds), "按退款发生日计算"],
    ["净收款", money(data.money.net), "模拟收款减模拟退款"],
    ["区间下单数", count + " 笔", "含后续取消的订单"],
  ]
    .map(
      ([name, value, description]) =>
        `<div class="metric"><div class="metric-label">${name}</div><div class="value">${value}</div><small>${description}</small></div>`,
    )
    .join(
      "",
    )}</div><section class="card stack"><h2>住宿间夜与入住率</h2><p>区间已登记住宿间夜 <strong>${roomNights}</strong>，配置总间夜 <strong>${capacity}</strong>，汇总比例 <strong>${capacity ? ((roomNights / capacity) * 100).toFixed(1) + "%" : "暂无可用分母"}</strong>。</p>${table(
    ["日期", "配置总房量", "已登记住宿间夜", "入住比例"],
    data.daily.map(
      (d) =>
        `<tr><td>${d.day}</td><td>${d.capacity}</td><td>${d.occupied}</td><td>${Number(d.capacity) ? ((Number(d.occupied) / Number(d.capacity)) * 100).toFixed(1) + "%" : "—"}</td></tr>`,
    ),
  )}<p class="technical-note">${esc(data.definition)}</p></section><div class="grid2"><section class="card stack"><h3>区间订单当前状态</h3>${
    data.orders.length
      ? table(
          ["状态", "数量"],
          data.orders.map(
            (s) => `<tr><td>${badge(s.status)}</td><td>${s.count}</td></tr>`,
          ),
        )
      : notice("该区间没有新增订单。")
  }</section><section class="card stack"><h3>当前实体房间状态</h3>${table(
    ["状态", "房间数"],
    data.rooms.map(
      (s) => `<tr><td>${badge(s.state)}</td><td>${s.count}</td></tr>`,
    ),
  )}</section></div>`;
}
async function logsPage(q) {
  const hotel = hotelId(q),
    page = Number(q.get("page") || 1);
  const data = await api(
    "/api/manage/logs?" + new URLSearchParams({ hotelId: hotel, page }),
  );
  const actions = {
    PAYMENT: "线上付款",
    OFFLINE_BOOKING: "到店预订",
    CONFIRM: "前台接单",
    CANCEL: "取消与退款",
    NO_SHOW: "未到店结束",
    CHECK_IN: "入住登记",
    CHECK_OUT: "退房结算",
    CLEAN: "清洁完成",
    EXTEND: "续住办理",
    SUBMIT_CHANGE: "提交资料",
    REVIEW_CHANGE: "审核资料",
    FEE_POLICY: "调整超时费率",
    OPEN_INCIDENT: "登记应急",
    CLOSE_INCIDENT: "恢复接待",
    RESOLVE_INCIDENT_CASE: "应急事项办结",
    RECEPTION_REQUEST: "接待事项登记",
    RESOLVE_RECEPTION: "接待事项办结",
  };
  return `${heading("操作日志", "保留关键业务动作、操作者和结果摘要；不记录密码或完整证件。")}${hotelFilter("logs", q)}${
    data.items.length
      ? table(
          ["时间", "操作者", "操作", "业务对象", "结果摘要"],
          data.items.map(
            (l) =>
              `<tr><td>${time(l.created_at)}</td><td>${esc(l.actor_name)}</td><td>${esc(actions[l.action] || l.action)}</td><td>${esc(l.object_type)} #${l.object_id}</td><td class="log-summary">${esc(l.summary)}</td></tr>`,
          ),
        )
      : empty(
          "还没有业务日志",
          "完成预订、接待或审核操作后，将在这里留下记录。",
        )
  }<div class="pagination"><span>共 ${data.total} 条，第 ${page} 页</span><div class="actions">${page > 1 ? `<a class="btn secondary small" href="${href("logs", { hotel, page: page - 1 })}">上一页</a>` : ""}${page * 30 < data.total ? `<a class="btn secondary small" href="${href("logs", { hotel, page: page + 1 })}">下一页</a>` : ""}</div></div>`;
}
export async function page(route, q) {
  if (route === "resume" && role("CUSTOMER")) {
    const values = new URLSearchParams(q);
    values.set("resume", q.get("id"));
    values.delete("id");
    return reservePage(values);
  }
  if (!state.user) {
    return empty(
      "请先登录",
      "工作台需要具有相应权限的账号。",
      button("login", "登录", "", ""),
    );
  }
  if (role("CUSTOMER")) {
    return empty(
      "该入口供酒店工作人员使用",
      "你可以从首页预订酒店，或查看自己的订单。",
      '<a class="btn" href="#orders">查看我的订单</a>',
    );
  }
  const staffPages = ["offline", "rooms", "checkin", "checkout", "extend"];
  if (staffPages.includes(route) && !role("STAFF")) {
    throw new Error("该操作仅由所属酒店前台办理。");
  }
  switch (route) {
    case "dashboard":
      return dashboard(q);
    case "offline":
      return reservePage(q, true);
    case "rooms":
      return roomsPage();
    case "checkin":
      return checkinPage(q);
    case "checkout":
      return checkoutPage(q);
    case "extend":
      return extendPage(q);
    case "manage":
      return managePage(q);
    case "changes":
      return changesPage(q);
    case "incidents":
      return incidentPage(q);
    case "statistics":
      return statistics(q);
    case "logs":
      return logsPage(q);
    default:
      return empty(
        "没有找到这个页面",
        "请通过导航栏进入需要的功能。",
        '<a class="btn" href="#home">返回首页</a>',
      );
  }
}
function simpleForm(title, type, content, submit, extras = "") {
  modal(
    title,
    `<form data-form="${type}" class="stack">${extras}${content}${errorBox}<div class="actions end">${button("close", "暂不操作")}<button type="submit" class="btn">${esc(submit)}</button></div></form>`,
  );
}
export async function action(name, id) {
  switch (name) {
    case "confirm-order": {
      const b = await api("/api/bookings/" + Number(id));
      state.order = b;
      simpleForm(
        "确认酒店接单",
        "confirm-order",
        `<p>订单 #${b.id} · ${esc(b.contact)}</p>${keyvals([
          ["住宿日期", `${b.check_in} → ${b.check_out}`],
          ["确认房量", b.rooms + "间"],
        ])}${notice("确认时重新核对全部住宿夜。无房可接或超过30分钟时会取消并全额退款；有更早付款订单优先占用同一房量时，会提示先处理早单。")}`,
        "核对库存并接单",
      );
      break;
    }
    case "clean":
      simpleForm(
        "确认客房清洁完成",
        "clean",
        "<p>请确认客房已完成清洁与检查，可供新住客入住。</p>",
        "确认清洁完成",
        hidden("id", id),
      );
      break;
    case "guest-records": {
      const data = await api(`/api/reception/bookings/${Number(id)}/guests`);
      modal(
        "住客登记记录",
        `<div class="stack">${notice("证件仅显示末四位，完整号码不在页面中返回。")}${table(
          ["姓名", "联系电话", "证件号码", "房号"],
          data.items.map(
            (g) =>
              `<tr><td>${esc(g.name)}</td><td>${esc(g.phone)}</td><td>${esc(g.document)}</td><td>${esc(g.number)}</td></tr>`,
          ),
        )}</div>`,
      );
      break;
    }
    case "reception-request":
      simpleForm(
        "登记接待事项",
        "reception-request",
        `<label>事项类型<select name="kind">${[
          ["EARLY_DEPARTURE", "提前退房"],
          ["OVERDUE_EXTENSION", "超过期限后申请续住"],
          ["OTHER", "其他接待事项"],
        ]
          .map(([v, t]) => option(v, t))
          .join(
            "",
          )}</select></label><label>情况说明<textarea name="reason" maxlength="300" required placeholder="记录当前情况与需要跟进的安排"></textarea></label>${notice("登记事项不会自动改变入住状态或产生退款。政策未明确的部分需人工核实后记录处理结果。", true)}`,
        "保存事项",
        hidden("id", id),
      );
      break;
    case "resolve-request":
      simpleForm(
        "记录接待事项结论",
        "resolve-request",
        '<label>实际处理结论<textarea name="resolution" required maxlength="300" placeholder="如实记录已沟通和完成的事项"></textarea></label>' +
          notice("此操作仅办结事项记录，不会自动退房或扣退款。"),
        "保存结论",
        hidden("id", id),
      );
      break;
    case "edit-info": {
      const data = state.manage;
      const info =
        id === "hotel"
          ? data.hotel
          : data.types.find((t) => t.id === Number(id));
      simpleForm(
        id === "hotel" ? "修改酒店资料" : "修改房型资料",
        "edit-info",
        `<label>名称<input name="name" value="${esc(info.name)}" required maxlength="${id === "hotel" ? 100 : 60}"></label><label>介绍<textarea name="description" required maxlength="2000">${esc(info.description)}</textarea></label><label>展示图片<select name="image" id="image-choice">${[
          info.image,
          "/assets/garden.jpg",
          "/assets/room.jpg",
          "/assets/suite.jpg",
        ]
          .filter((x, i, a) => a.indexOf(x) === i)
          .map((url, i) =>
            option(url, i === 0 ? "当前图片" : "内置图片 " + i, info.image),
          )
          .join(
            "",
          )}</select></label><label>或上传新图片<input type="file" accept="image/png,image/jpeg" data-change="upload"><span class="form-note">JPEG / PNG，不超过5MB，2000万像素以内。</span></label><div id="upload-feedback" class="technical-note"></div>${notice("提交后保持当前展示不变；管理员批准后才发布新资料。")}`,
        "提交审核",
        hidden("kind", id === "hotel" ? "HOTEL" : "ROOM_TYPE") +
          hidden("targetId", info.id) +
          hidden("version", info.version),
      );
      break;
    }
    case "inventory-edit": {
      const data = state.manage,
        first = data.inventory.find((i) => i.stay_date >= state.today);
      simpleForm(
        "调整房价与房量",
        "inventory-edit",
        `<label>房型<select name="targetId" data-change="inventory-type">${data.types.map((t) => option(t.id, t.name, first?.room_type_id)).join("")}</select></label><div class="grid2"><label>开始住宿日<input type="date" name="start" value="${state.today}" min="${state.today}" max="${day(state.today, 364)}" required></label><label>结束日期（不含）<input type="date" name="end" value="${day(state.today, 1)}" min="${day(state.today, 1)}" max="${day(state.today, 365)}" required></label><label>每日总房量<input type="number" name="capacity" value="${first?.capacity || 0}" min="0" max="500" step="1" required></label><label>门市价（元）<input type="number" name="publicYuan" value="${(first?.public_price || 0) / 100}" min="0" max="1000000" step="0.01" required></label><label>线上价（元）<input type="number" name="onlineYuan" value="${(first?.online_price || 0) / 100}" min="0" max="1000000" step="0.01" required></label></div><label class="checkbox"><input type="checkbox" name="stopSale">常规停售所选住宿夜</label>${notice("一次最多调整30天。房量不能超过实体房间数，也不能低于已承诺数量；已有有效预订的日期不能直接停售，需要走应急处理。")}`,
        "提交审核",
      );
      break;
    }
    case "review-approve":
    case "review-reject":
      simpleForm(
        name === "review-approve" ? "批准并发布资料" : "驳回资料申请",
        "review",
        `<label>审核意见<textarea name="note" required maxlength="300" placeholder="记录本次审核依据或需要修正的内容"></textarea></label>${notice(name === "review-approve" ? "发布时再次核对资料版本、已承诺房量和日期；存在冲突时不会覆盖现有数据。" : "驳回后当前对外资料不变，前台可根据意见重新提交。")}`,
        name === "review-approve" ? "批准并发布" : "确认驳回",
        hidden("id", id) + hidden("approve", name === "review-approve"),
      );
      break;
    case "fee-edit":
      simpleForm(
        "设置超时退房费率",
        "fee",
        '<label>每订单每小时收费（元）<input name="yuan" type="number" min="0" max="10000" step="0.01" required></label>' +
          notice(
            "只影响此后办理入住的订单，已经在住的订单继续使用入住时费率。",
          ),
        "保存新费率",
        hidden("id", id),
      );
      break;
    case "incident-new":
      simpleForm(
        "登记接待中断情况",
        "incident-preview",
        `<label>受影响房型<select name="roomTypeId">${state.incidentTypes.map((t) => option(t.id, t.name)).join("")}</select></label><div class="grid2"><label>开始住宿日<input name="start" type="date" value="${state.today}" min="${state.today}" max="${day(state.today, 364)}" required></label><label>结束日期（不含）<input name="end" type="date" value="${day(state.today, 1)}" min="${day(state.today, 1)}" max="${day(state.today, 365)}" required></label></div><label>事件原因<textarea name="reason" required maxlength="300" placeholder="说明接待中断原因与预计影响范围"></textarea></label>${notice("先预览受影响订单。最终确认时服务器重新计算范围，并在同一事务内完成停售及未入住订单退款。", true)}`,
        "预览影响范围",
      );
      break;
    case "incident-resolve": {
      const [incident, booking] = id.split(":");
      simpleForm(
        "记录在住应急处理结论",
        "incident-resolve",
        '<label>处理结论<textarea name="note" required maxlength="300" placeholder="记录与住客的沟通及实际安排"></textarea></label>' +
          notice("仅记录人工处理结论；不自动套用未入住全退规则。"),
        "保存处理结论",
        hidden("incident", incident) + hidden("booking", booking),
      );
      break;
    }
    case "incident-close":
      simpleForm(
        "确认恢复接待",
        "incident-close",
        '<label class="checkbox"><input type="checkbox" name="restored" required>受影响房型在所选日期已恢复接待能力。</label>' +
          notice(
            "所有在住人工事项须先记录处理结论。其他仍开放的事件及常规停售设置会继续生效。",
          ),
        "关闭事件并恢复",
        hidden("id", id),
      );
      break;
    default:
      throw new Error("暂不支持此操作，请刷新页面。");
  }
}
export async function form(form, data) {
  let result;
  switch (form.dataset.form) {
    case "view-filter": {
      const route = data.route;
      delete data.route;
      go(route, data);
      return;
    }
    case "confirm-order": {
      const b = state.order;
      result = await mutation(
        `/api/bookings/${b.id}/confirm`,
        { version: b.version },
        form,
      );
      toast(result.message || "酒店接单成功");
      break;
    }
    case "clean":
      await mutation(`/api/reception/rooms/${Number(data.id)}/clean`, {}, form);
      toast("清洁已确认，房间可以安排入住");
      break;
    case "checkin": {
      const order = state.order;
      const assignments = Array.from({ length: order.rooms }, (_, i) => ({
        roomId: Number(data["room_" + i]),
        guests: [],
      }));
      for (let i = 0; i < order.guest_count; i++) {
        assignments[Number(data["guest_room_" + i])].guests.push({
          name: data["guest_name_" + i],
          phone: data["guest_phone_" + i],
          document: data["guest_document_" + i],
        });
      }
      if (assignments.some((a) => !a.guests.length)) {
        throw new Error("每间房至少安排一位住客，请调整住客分配。");
      }
      await mutation(
        `/api/reception/bookings/${order.id}/checkin`,
        {
          version: order.version,
          assignments,
          identityVerified: checked(form, "identityVerified"),
          proxyBooking: checked(form, "proxyBooking"),
          phoneVerified: checked(form, "phoneVerified"),
        },
        form,
      );
      go("order", { id: order.id });
      toast("全部住客已登记，入住办理成功");
      return;
    }
    case "checkout":
      await mutation(
        `/api/reception/bookings/${state.order.id}/checkout`,
        {
          version: state.order.version,
          expectedFee: state.checkout.fee,
          paymentConfirmed: checked(form, "paymentConfirmed"),
        },
        form,
      );
      go("order", { id: state.order.id });
      toast("退房完成，客房已转为待清洁");
      return;
    case "extend-quote": {
      const quote = await api(
        `/api/reception/bookings/${state.order.id}/extend?` +
          new URLSearchParams({ end: data.end }),
      );
      state.extension = quote;
      $("#extension-result").innerHTML =
        `<form data-form="extend-confirm" class="card stack"><h3>续住费用确认</h3>${table(
          ["新增住宿夜", "每间单价", "房量"],
          quote.nights.map(
            (n) =>
              `<tr><td>${n.day}</td><td>${money(n.unitPrice)}</td><td>${state.order.rooms}间</td></tr>`,
          ),
        )}<div class="price-line"><span>本次补款</span><strong class="money">${money(quote.amount)}</strong></div><label class="checkbox"><input name="paymentConfirmed" type="checkbox" required>已核对新增行程及补款金额，确认模拟收款。</label>${errorBox}<button type="submit" class="btn">确认续住至 ${quote.end}</button></form>`;
      $("#extension-result").scrollIntoView({
        behavior: "smooth",
        block: "nearest",
      });
      return;
    }
    case "extend-confirm":
      await mutation(
        `/api/reception/bookings/${state.order.id}/extend`,
        {
          end: state.extension.end,
          version: state.extension.version,
          priceHash: state.extension.priceHash,
          paymentConfirmed: checked(form, "paymentConfirmed"),
        },
        form,
      );
      go("order", { id: state.order.id });
      toast("续住成功，新增费用已保存");
      return;
    case "reception-request":
      await mutation(
        `/api/reception/bookings/${Number(data.id)}/requests`,
        { kind: data.kind, reason: data.reason },
        form,
      );
      toast("接待事项已登记");
      break;
    case "resolve-request":
      await mutation(
        `/api/reception/requests/${Number(data.id)}/resolve`,
        { resolution: data.resolution },
        form,
      );
      toast("接待事项处理结论已保存");
      break;
    case "edit-info":
      await mutation(
        "/api/manage/changes",
        {
          kind: data.kind,
          targetId: Number(data.targetId),
          version: Number(data.version),
          name: data.name,
          description: data.description,
          image: data.image,
        },
        form,
      );
      toast("资料已提交，等待管理员审核");
      break;
    case "inventory-edit":
      await mutation(
        "/api/manage/changes",
        {
          kind: "INVENTORY",
          targetId: Number(data.targetId),
          start: data.start,
          end: data.end,
          capacity: Number(data.capacity),
          publicPrice: Math.round(Number(data.publicYuan) * 100),
          onlinePrice: Math.round(Number(data.onlineYuan) * 100),
          stopSale: checked(form, "stopSale"),
        },
        form,
      );
      toast("房价与房量申请已提交");
      break;
    case "review":
      await mutation(
        `/api/manage/changes/${Number(data.id)}/review`,
        { approve: data.approve === "true", note: data.note },
        form,
      );
      toast(
        data.approve === "true" ? "已批准并发布资料" : "已驳回并保留审核意见",
      );
      break;
    case "fee":
      await mutation(
        `/api/manage/hotels/${Number(data.id)}/fee`,
        { hourlyRate: Math.round(Number(data.yuan) * 100) },
        form,
      );
      toast("新费率已保存");
      break;
    case "incident-preview": {
      const values = {
        roomTypeId: Number(data.roomTypeId),
        start: data.start,
        end: data.end,
        reason: data.reason,
      };
      const preview = await api(
        "/api/manage/incidents/preview?" + new URLSearchParams(values),
      );
      state.incidentDraft = values;
      simpleForm(
        "确认应急影响范围",
        "incident-open",
        `<p>${esc(data.reason)}</p><p class="technical-note">${data.start} 至 ${data.end}（结束日不含）</p>${
          preview.items.length
            ? table(
                ["订单", "联系人", "当前状态", "处理方式"],
                preview.items.map(
                  (b) =>
                    `<tr><td>#${b.id}</td><td>${esc(b.contact)}</td><td>${badge(b.status)}</td><td>${b.status === "CHECKED_IN" ? "人工处理" : "全额退款 " + money(b.amount)}</td></tr>`,
                ),
              )
            : notice("目前没有受影响的有效订单。")
        }<label class="checkbox"><input type="checkbox" name="confirmed" required>确认停止所选住宿夜销售，并对受影响的未入住订单全额退款。</label>`,
        "确认停售并处理",
      );
      return;
    }
    case "incident-open":
      result = await mutation(
        "/api/manage/incidents",
        { ...state.incidentDraft, confirmed: checked(form, "confirmed") },
        form,
      );
      toast(
        `事件已登记：全退${result.refunded}单，在住待处理${result.manual}单`,
      );
      break;
    case "incident-resolve":
      await mutation(
        `/api/manage/incidents/${Number(data.incident)}/cases/${Number(data.booking)}`,
        { note: data.note },
        form,
      );
      toast("在住处理结论已保存");
      break;
    case "incident-close":
      await mutation(
        `/api/manage/incidents/${Number(data.id)}/close`,
        { restored: checked(form, "restored") },
        form,
      );
      toast("事件已关闭，已重新计算停售状态");
      break;
    default:
      throw new Error("未识别的表单，请刷新页面后重试。");
  }
  closeModal();
  await render();
}
export async function change(element) {
  if (element.dataset.change === "proxy") {
    $("#proxy-check").hidden = !element.checked;
    element.form.elements.phoneVerified.required = element.checked;
  }
  if (element.dataset.change === "upload") {
    const file = element.files[0];
    if (!file) {
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      throw new Error("图片不能超过5MB。");
    }
    const button = $("button[type=submit]", element.form);
    button.disabled = true;
    $("#upload-feedback").textContent = "正在检查并上传图片…";
    try {
      const data = new FormData();
      data.append("file", file);
      const result = await api("/api/manage/images", data);
      const select = $("#image-choice");
      const o = document.createElement("option");
      o.value = result.url;
      o.textContent = "刚上传的图片";
      select.add(o);
      select.value = result.url;
      $("#upload-feedback").textContent =
        "上传成功，提交资料并通过审核后才对外展示。";
    } catch (error) {
      $("#upload-feedback").textContent = error.message;
    } finally {
      button.disabled = false;
    }
  }
  if (element.dataset.change === "inventory-type") {
    const row = state.manage.inventory.find(
      (i) =>
        i.room_type_id === Number(element.value) && i.stay_date >= state.today,
    );
    if (row) {
      element.form.elements.capacity.value = row.capacity;
      element.form.elements.publicYuan.value = row.public_price / 100;
      element.form.elements.onlineYuan.value = row.online_price / 100;
    }
  }
}
