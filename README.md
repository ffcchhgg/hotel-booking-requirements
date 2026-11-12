# 酒店预订系统 · 软件工程课程项目

本仓库从实验一的需求分析继续推进到实验二的软件设计与建模。当前保存需求基线、设计说明、UML、可交互网页原型和报告；后续实验三将以此为起点实现业务服务与数据持久化。

项目目前是**设计与浏览器交互原型**。付款、退款、身份和业务数据均为演示，数据只保存在当前浏览器标签页会话中；尚未实现正式服务端鉴权、数据库事务、真实支付或后台定时任务。

## 从哪里开始看

1. 阅读[实验二报告](exp_2/report/report_v2.pdf)，了解功能、架构、模型与交互设计。
2. 下载或克隆仓库，用浏览器打开 [exp_2/prototype/index.html](exp_2/prototype/index.html)，按一次完整预订任务体验界面。GitHub 文件页本身只展示源码，不会直接运行网页。
3. 查看[彩色 UML](exp_2/deliverables/UML_color)与[完整 UI 截图](exp_2/deliverables/UI界面)。报告中的截图是排版用局部裁图，此处保留完整页面。
4. 需要核对规则或检查证据时，使用下方的材料索引。

## 当前阶段

- 实验一：28条需求，19条已确认、9条仍有待确认内容；原需求及访谈记录保留在仓库根目录，沿用原有 Git 历史。
- 实验二：9个模块，6种共9张 UML，3类角色的网页原型，26张完整界面截图，以及35页彩色报告。
- 已处理的交互问题：固定角色导航，明确查房起点，登录后返回原预订表单；顾客订单与前台接单共享演示数据；人数改为1—30的整数输入，按房型容量计算多间房数量和总价。
- 后续实现：数据库、服务端权限、并发及回滚、后台超时任务、真实持久化等。52项业务场景是后续验证计划，不代表已经全部执行。

## 运行原型

无需安装 Node.js 或启动服务，直接在现代浏览器中打开 `exp_2/prototype/index.html`。图片保存在仓库内，可离线查看。

推荐体验顺序：查房 → 查看房型 → 填写预订 → 使用演示账号登录 → 模拟付款 → 页面底部进入前台工作台 → 接单 → 返回顾客端查看订单。

登录页支持“使用演示账号体验”，也可填写 `guest / demo1234`。工作人员入口在页脚。切换角色时保持同一个浏览器标签页；页脚“重置演示”可恢复初始数据。所有账户、住客、订单和款项均为演示数据。

## 材料与检查记录索引

| 材料 | 位置 | 用途 |
| --- | --- | --- |
| 需求基线 | [requirements.md](requirements.md) | 实验一已保存的需求和确认状态 |
| 需求访谈记录 | [chat.txt](chat.txt) | 已公开的实验一访谈及确认记录 |
| 架构与边界 | [01_架构与边界.md](exp_2/design/01_架构与边界.md) | 模块分工、数据和事务设计 |
| 候选规则 | [02_设计假设与待决事项.md](exp_2/design/02_设计假设与待决事项.md) | A01—A16的完整定义，不作为甲方已确认结论 |
| 权限和状态 | [03_权限状态与不变量.md](exp_2/design/03_权限状态与不变量.md) | 角色范围、订单状态和关键业务约束 |
| 需求映射 | [04_需求覆盖矩阵.md](exp_2/design/04_需求覆盖矩阵.md) | 需求与模块、UML、UI、验证场景的对应关系 |
| 验证计划 | [05_验证场景.md](exp_2/design/05_验证场景.md) | 52项计划场景；不等于执行结果 |
| UI设计说明 | [06_UI设计清单.md](exp_2/design/06_UI设计清单.md) | 页面、入口和操作流程 |
| 当前报告用 UML | [deliverables/UML_color/](exp_2/deliverables/UML_color) | 9份PlantUML源码及对应彩色PNG |
| 原配色 UML | [deliverables/UML/](exp_2/deliverables/UML) | 相同模型的原黑白配色，供源图比较 |
| 完整界面截图 | [deliverables/UI界面/](exp_2/deliverables/UI界面) | 26张完整页面及关键状态PNG |
| 原型与照片来源 | [prototype/](exp_2/prototype) · [SOURCES.md](exp_2/prototype/assets/SOURCES.md) | HTML/CSS/JavaScript和本地示意图片来源 |
| 主要交互检查 | [ui_journeys.json](exp_2/checks/ui_journeys.json) | 15组任务与布局检查，含19条路由的桌面/手机检查 |
| 人数输入检查 | [guest_count.json](exp_2/checks/guest_count.json) | 5组人数、容量、价格和登录回跳检查 |
| 截图来源清单 | [ui.json](exp_2/checks/ui.json) | 截图场景、尺寸及源码/图片SHA-256 |
| 报告检查 | [report_publication.json](exp_2/checks/report_publication.json) | PDF与LaTeX摘要、仓库链接和源码包独立编译结果 |
| AI辅助过程 | [AI辅助记录.md](exp_2/process/AI辅助记录.md) | 辅助工作、真实反馈及修订过程的事实索引 |
| 报告源文件 | [report_v2.tex](exp_2/report/report_v2.tex) | 用户修订的正文、彩图和仓库版本说明 |

过程文件中的 `guide/`、`work/` 等路径指本地老师资料或历史预览，不属于公开仓库的运行依赖。实验一的两个文件在本仓库根目录；旧工作记录中的 `exp_1/` 是本地实验目录名称。

## 检查的范围与复现方法

浏览器检查验证前端任务路径、表单边界、演示数据联动和页面布局；它们不证明真实系统的安全性、数据库并发或支付可靠性。本次同步前重新运行了15组主要任务检查和5组人数检查，均通过。

安装 Node.js 20或更高版本后，从仓库根目录执行：

```sh
cd exp_2
npm ci
npx playwright install chromium
npm test
```

检查脚本使用项目安装的 Playwright。需要指定现有 Chromium 时，可设置 `PLAYWRIGHT_EXECUTABLE_PATH`；默认使用 Playwright 安装的浏览器。脚本不依赖作者电脑的固定目录。

素材一致性检查使用 Python 3.10或更高版本：

```sh
cd exp_2
python -m pip install -r requirements-tools.txt
python tools/verify_materials.py
```

它会核对当前原型与既有结果的源码摘要、26张完整截图、两套配色的UML源图对应关系，以及21张报告裁图的像素来源。

## 报告编译

安装 XeLaTeX，并提供宋体（SimSun）、Times New Roman、Arial 和 Consolas。进入 `exp_2/report/`，运行两遍：

```sh
xelatex -no-shell-escape -interaction=nonstopmode -halt-on-error report_v2.tex
xelatex -no-shell-escape -interaction=nonstopmode -halt-on-error report_v2.tex
```

请保留 `report/assets_color/` 与 `deliverables/UML_color/` 的相对位置。生成的PDF可独立阅读。课堂提交仍按老师要求整理报告、UML和UI截图；GitHub保存便于继续开发的工程材料。重复ZIP、编译中间文件、老师资料和旧版临时预览不纳入仓库。

## 版本记录

实验一的原始提交历史保留。实验二的设计与原型阶段提交为 [`0070cfe8628b7`](https://github.com/ffcchhgg/hotel-booking-requirements/commit/0070cfe8628b76083660e73c900c99d5d788afa9)。报告引用这一固定版本；后续提交补充仓库说明和最终报告，不改变该设计版本中的模型及原型。

`lab2-design-v1` 固定上述设计版本，`lab2-delivery-v1` 固定附有仓库说明的报告及对应工程材料。
