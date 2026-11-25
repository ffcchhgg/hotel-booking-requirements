# 实验三：酒店预订系统实现与构建

这里保存可以运行的 Java Web 应用、数据库迁移、自动化测试与实现说明。实验一的需求基线、实验二的模型和原型继续保留；本目录中的工程文档不是实验报告。本轮没有编写报告或打包提交文件。

## 先运行起来

环境要求：JDK 21、Docker Desktop（Linux containers）、能连接 Maven Central 的网络。Node.js 只用于浏览器验收和格式检查，正常运行不需要 Node 服务。首次构建会下载依赖。

在 PowerShell 中进入 `exp_3/code`，执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start.ps1
```

脚本会启动 PostgreSQL、使用 Maven Wrapper 构建并检查、在本机后台启动 Java 应用，等待健康检查成功。打开 **http://127.0.0.1:8080**。数据库端口为 `127.0.0.1:55432`，默认应用只监听本机。

关闭应用：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop.ps1
```

数据库数据保留在 Docker 的 `hotel_data` 持久卷中。退出应用、关闭页面、重启 Java 都不会清空订单。不要执行 `docker compose down -v`，除非确实准备清空本地数据库。

脚本中的 `hotel-local-development-2026` 是本机课程演示数据库密码；也可在第一次建库前设置 `DB_PASSWORD`。已有持久卷的数据库密码不会随环境变量自动改变。后台输出在 `code/.runtime/`，构建产物在 `code/target/`；这些文件不提交 Git。服务器运行时使用 `.runtime/` 中的 JAR 副本，因此可以重新构建 `target/`。

## 演示账号

以下账号仅在设置 `HOTEL_DEMO=true` 且数据库没有酒店记录时初始化。统一演示密码为 **`DemoHotel2026!`**，数据库中保存 BCrypt 散列。

| 账号 | 角色 | 可操作范围 |
| --- | --- | --- |
| `guest`、`guest2` | 顾客 | 各自的订单 |
| `staff1` | 前台 | 梧桐院 |
| `staff2` | 前台 | 青禾馆 |
| `staff3` | 前台 | 书庭居 |
| `admin` | 管理员 | 酒店资料审核、统计、日志、费率；不代客取消或接单 |

注册入口只创建普通顾客。电话、校园卡和住客身份核验由前台勾选确认，支付、退款均为模拟业务记录，没有银行、电话或公安平台调用。住客登记请使用虚构证件号码，例如 `TEST000001`。

## 推荐验收顺序

1. 以顾客查房，填写7位人数，观察系统按房型容量建议多间房；选择行程、核对逐晚费用并模拟付款。
2. 顾客订单应为“待酒店确认”。另开无痕窗口登录 `staff1`，从订单接待确认该酒店订单；回到顾客端刷新，状态应为“待入住”。
3. 对今天入住的订单登记全部住客，选择同房型且已清洁的实体房间；办理续住后检查新增夜费用和新离店日。
4. 另建未来订单，分别试用取消和修改；查看资金记录与原、新订单关联。已取消订单不能重新接单。
5. 前台调整房价或酒店介绍，管理员审核前顾客仍看到旧资料，批准后新资料才生效。
6. 前台登记某房型某日期的应急事件：先预览，再确认停售与未入住全退；有在住订单时须单独记录处理结论。
7. 管理员查看订单、经营统计与操作日志；退出后重新登录确认数据保留。

正常退房须等到原定离店日期；不会为了方便演示在产品页面增加“修改服务器时间”开关。自动化验收在隔离测试库中使用明确的时钟与历史入住夹具验证退房、超时及清洁，见下方证据。

## 代码与材料放在哪里

| 路径 | 内容 |
| --- | --- |
| `code/src/main/java/cn/edu/hotel/` | 身份、报价、预订、接待、管理、应急和自动任务 |
| `code/src/main/resources/db/migration/` | PostgreSQL 结构与版本化迁移 |
| `code/src/main/resources/static/` | 真实接口驱动的顾客、前台与管理员页面 |
| `code/src/test/java/` | 2 项规则测试、51 项 PostgreSQL 集成测试 |
| `code/tests/` | 浏览器流程、退房与清洁、100 次房源查询专项验收 |
| `code/scripts/` | 启动、停止、集成测试、隔离夹具和重启验证 |
| [实现决策与边界](docs/01_实现决策与边界.md) | 已确认需求、候选规则、与实验二的实际差异 |
| [覆盖与验证索引](docs/02_覆盖与验证索引.md) | 需求对应实现及验证范围，含尚未覆盖的候选细节 |
| [代码阅读与操作接口](docs/03_代码阅读与接口.md) | 源码阅读顺序、事务设计、主要接口 |
| [阶段结果](evidence/阶段结果.md) | 实测结果、发现的问题、修复记录 |
| `docs/UML/` | 4张与当前实现对应的 PlantUML 源文件和 PNG |
| `evidence/*.json` | 构建、浏览器、性能、重启及退房验收原始摘要 |
| `evidence/*.png` | 实际浏览器截图 |

## 自动化验证

规则、代码规范和数据库集成测试：

```powershell
cd code
powershell -ExecutionPolicy Bypass -File scripts/test.ps1
```

只在 `hotel_test` 中重置夹具，测试入口验证数据库名以 `_test` 结尾，避免误清空运行库。事务测试不使用内存数据库，包含故障注入、20并发接单、8并发同请求提交以及访问权限。详细 XML 在 `target/surefire-reports` 和 `target/failsafe-reports`。

浏览器验收需要独立数据库和端口：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Port 8081 -Database hotel_e2e_test -SkipBuild
npm ci
npx playwright install chromium
npm run test:e2e
```

浏览器主脚本用于初始演示夹具。重复执行前先停止8081服务，再运行 `scripts/reset-e2e.ps1`，最后重新启动以初始化演示数据；该脚本只允许明确命名的隔离测试数据库。浏览器无需图形窗口，截图和结果写入 `evidence/`。若已有 Playwright 浏览器，可设置 `PLAYWRIGHT_EXECUTABLE_PATH` 使用相应可执行文件。

专项验收在主浏览器流程之后运行，避免历史数据影响流程截图：

```powershell
$env:DB_PASSWORD = 'hotel-local-development-2026'
Get-Content scripts/performance-seed.sql -Raw | docker compose exec -T db psql -v ON_ERROR_STOP=1 -U hotel -d hotel_e2e_test
npm run test:performance
```

脚本按 REQ-026 执行五轮各 20 次房源查询。登录、订单总数校验和预热单独执行。正式记录为 `room-search-results.json`。可设置 `EVIDENCE_DIR` 将本次浏览器、退房、重启和性能记录写入另一目录。

退房页面验收：先将 `scripts/checkout-fixture.sql` 以同样方式导入隔离测试库，再执行 `node tests/checkout.cjs`。重启一致性验收：`powershell -ExecutionPolicy Bypass -File scripts/check-restart.ps1`。存在待接单恰好跨过30分钟时，自动任务可能产生合法变化，需要先处理该订单再验证稳定数据，不能把合法超时误判为数据丢失。

## 当前结果与范围

2026-11-25 复验通过 53 项后端测试、8 组主浏览器流程和 1 组退房清洁流程。以 10,006 笔订单、20 个会话执行 100 次房源查询，全部成功且不超过 2 秒。第 95 百分位耗时 11.55 ms，最大 13.34 ms。Java 进程重启前后订单、款项和逐夜记录摘要一致。[本轮验收证据](evidence/2026-11-25/)

此前 `evidence/performance-results.json` 对应 80 次订单查询和 20 次房源查询的混合负载。REQ-026 的判定以本轮 `room-search-results.json` 为准。

当前每个订单支持**同一酒店、同一个房型的多间房**。实验二候选A02中的混合房型整单付款未实现，混合房型需要分别下单；原“待确认”需求状态没有因此改为“已确认”。提前退房退款、超期续住、在住应急补偿保留人工事项登记，不自动决定未确认的退款政策。详情见实现决策文档。

本实现由AI辅助完成，自动检查记录不等于学生已逐行审阅或已独立完成实验报告。后续应由学生实际运行、理解关键代码并记录自己的操作与认识。
