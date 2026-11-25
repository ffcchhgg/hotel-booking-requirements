# 酒店预订系统

本项目记录了软件工程课程的三个实验。实验三提供可运行的 Java / PostgreSQL 应用，包含顾客预订、前台接待和管理员审核。付款、退款与身份核验采用课程模拟流程。

| 实验 | 从这里开始 |
| --- | --- |
| 一 · 需求分析 | [需求规格说明](requirements.md) · [访谈与确认记录](chat.txt) |
| 二 · 软件设计与建模 | [实验二说明](exp_2/README.md) · [设计报告](exp_2/report/report_v2.pdf) · [交互原型](exp_2/prototype/index.html) |
| 三 · 软件实现与构建 | [运行和测试说明](exp_3/README.md) · [应用源码](exp_3/code/) · [实现 UML](exp_3/docs/UML/) · [验收结果](exp_3/evidence/2026-11-25/) |

## 运行实验三

在 Windows 上准备 JDK 21 和 Docker Desktop，然后在仓库根目录用 PowerShell 启动。

```powershell
cd exp_3/code
powershell -ExecutionPolicy Bypass -File scripts/start.ps1
```

启动成功后，在浏览器访问 [http://127.0.0.1:8080](http://127.0.0.1:8080)。演示账号、停止方法和测试命令见 [实验三运行说明](exp_3/README.md)。

本轮验收通过 53 项后端测试和 9 组浏览器流程。100 次房源查询均在两秒内成功返回，重启前后订单、款项和逐夜占用摘要一致。[验收原始记录](exp_3/evidence/2026-11-25/)

## 历史版本

- [实验一初稿](https://github.com/ffcchhgg/hotel-booking-requirements/tree/lab1-ai-draft) · [实验一定稿](https://github.com/ffcchhgg/hotel-booking-requirements/tree/lab1-final)
- [实验二设计](https://github.com/ffcchhgg/hotel-booking-requirements/tree/lab2-design-v1) · [实验二交付](https://github.com/ffcchhgg/hotel-booking-requirements/tree/lab2-delivery-v1)
