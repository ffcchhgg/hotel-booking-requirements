# 实验二 · 软件设计与建模

本目录保存酒店预订系统的 UML、可交互原型和实验二报告。[报告 PDF](report/report_v2.pdf) · [彩色 UML](deliverables/UML_color/) · [完整界面截图](deliverables/UI界面/) · [交互原型](prototype/index.html)

实验二原型使用浏览器会话保存演示数据。要体验含 Java 服务和 PostgreSQL 持久化的版本，请查看 [实验三运行说明](../exp_3/README.md)。

## 运行原型

无需安装 Node.js 或启动服务，直接在现代浏览器中打开 `exp_2/prototype/index.html`。图片保存在仓库内，可离线查看。

推荐体验顺序：查房 → 查看房型 → 填写预订 → 使用演示账号登录 → 模拟付款 → 页面底部进入前台工作台 → 接单 → 返回顾客端查看订单。

登录页支持“使用演示账号体验”，也可填写 `guest / demo1234`。工作人员入口在页脚。切换角色时保持同一个浏览器标签页；页脚“重置演示”可恢复初始数据。所有账户、住客、订单和款项均为演示数据。

## 材料与检查记录索引

| 材料 | 位置 | 用途 |
| --- | --- | --- |
| 需求基线 | [requirements.md](../requirements.md) | 实验一已保存的需求和确认状态 |
| 需求访谈记录 | [chat.txt](../chat.txt) | 已公开的实验一访谈及确认记录 |
| 架构与边界 | [01_架构与边界.md](design/01_架构与边界.md) | 模块分工、数据和事务设计 |
| 候选规则 | [02_设计假设与待决事项.md](design/02_设计假设与待决事项.md) | A01—A16的完整定义，不作为甲方已确认结论 |
| 权限和状态 | [03_权限状态与不变量.md](design/03_权限状态与不变量.md) | 角色范围、订单状态和关键业务约束 |
| 需求映射 | [04_需求覆盖矩阵.md](design/04_需求覆盖矩阵.md) | 需求与模块、UML、UI、验证场景的对应关系 |
| 验证计划 | [05_验证场景.md](design/05_验证场景.md) | 52项计划场景；不等于执行结果 |
| UI设计说明 | [06_UI设计清单.md](design/06_UI设计清单.md) | 页面、入口和操作流程 |
| 当前报告用 UML | [deliverables/UML_color/](deliverables/UML_color) | 9份PlantUML源码及对应彩色PNG |
| 原配色 UML | [deliverables/UML/](deliverables/UML) | 相同模型的原黑白配色，供源图比较 |
| 完整界面截图 | [deliverables/UI界面/](deliverables/UI界面) | 26张完整页面及关键状态PNG |
| 原型与照片来源 | [prototype/](prototype) · [SOURCES.md](prototype/assets/SOURCES.md) | HTML/CSS/JavaScript和本地示意图片来源 |
| 主要交互检查 | [ui_journeys.json](checks/ui_journeys.json) | 15组任务与布局检查，含19条路由的桌面/手机检查 |
| 人数输入检查 | [guest_count.json](checks/guest_count.json) | 5组人数、容量、价格和登录回跳检查 |
| 截图来源清单 | [ui.json](checks/ui.json) | 截图场景、尺寸及源码/图片SHA-256 |
| 报告检查 | [report_publication.json](checks/report_publication.json) | PDF与LaTeX摘要、仓库链接和源码包独立编译结果 |
| AI辅助过程 | [AI辅助记录.md](process/AI辅助记录.md) | 辅助工作、真实反馈及修订过程的事实索引 |
| 报告源文件 | [report_v2.tex](report/report_v2.tex) | 用户修订的正文、彩图和仓库版本说明 |

过程文件中的 `guide/`、`work/` 等路径指本地老师资料或历史预览，不属于公开仓库的运行依赖。实验一的两个文件在本仓库根目录；旧工作记录中的 `exp_1/` 是本地实验目录名称。

## 检查的范围与复现方法

浏览器检查验证前端任务路径、表单边界、演示数据联动和页面布局；它们不证明真实系统的安全性、数据库并发或支付可靠性。本次同步前重新运行了15组主要任务检查和5组人数检查，均通过。

安装 Node.js 20或更高版本后，在仓库根目录执行：

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

## 固定版本

Git 标签 [lab2-design-v1](https://github.com/ffcchhgg/hotel-booking-requirements/tree/lab2-design-v1) 对应设计与原型阶段。[lab2-delivery-v1](https://github.com/ffcchhgg/hotel-booking-requirements/tree/lab2-delivery-v1) 对应报告及配套工程材料。
