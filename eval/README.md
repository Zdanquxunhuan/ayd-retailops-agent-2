# V1.8 可复现对照评测

[真实百炼首轮报告](results/live.md) · [逐单轨迹](results/live.json) · [离线回归报告](results/offline.md) · [代表性样本复测](results/repeat.md)

三组共用生产项目的建案、门店权限、订单和日志适配器，分别新建案件。源数据只来自测试 classpath，工单状态在独立 H2 内存库，评测没有美团、真 POS 或补单适配器入口。C 测的是项目已有的有预算调查循环 AgentRunner，包括 Java 当前状态快捷路径。V1.7 真实工单的归档意图路由和 50 行上下文搜索是另外的功能，本轮数字不能直接外推给它们。

## 运行

从项目根目录在 PowerShell 执行：

~~~powershell
.\project-mvn.cmd -B -ntp '-Dtest=ComparativeEvalTest' test
python eval/report.py target/eval/v1.8-offline.json target/eval/offline.md
~~~

离线回放使用通用的脚本化工具选择器，只看本轮可见观测。可在干净的 Java 21 和 Maven 环境中用 `mvn` 替换 Windows 的项目启动脚本。用 Docker 时执行 `docker compose -f eval/compose.yaml run --rm replay`，输出位于 `eval/output`；首次拉取镜像和 Maven 依赖需要联网。容器执行同一离线测试，不加载本机凭据或业务数据。

有百炼授权时，给当前进程设置 `DASHSCOPE_API_KEY`，按现有配置选择官方 `DASHSCOPE_BASE_URL`。运行真实合成评测：

~~~powershell
.\project-mvn.cmd -B -ntp '-Dtest=ComparativeEvalTest' '-Deval.mode=live' test
python eval/report.py target/eval/v1.8-live.json target/eval/live.md
~~~

两笔单案回放分别演示延迟写库与处理证据缺失。可用 `-Deval.repeats=2` 重复整组或子集，输出按循环顺序排列。同次运行内使用相同的模型配置，模型请求失败则整次评测失败，先检查 Maven 的退出结果再使用产物。

~~~powershell
.\project-mvn.cmd -B -ntp '-Dtest=ComparativeEvalTest' '-Deval.orders=810007,810009' test
python eval/report.py target/eval/v1.8-offline-subset.json target/eval/examples.md
~~~

## 实验契约

- A 使用 Java 当前状态规则。遇到历史问题只返回证据不足。B 固定检索业务日北京时间 10:00–10:10 的 GATEWAY 和 POS_WRITER，各取至多 8 条，随后用与 C 相同的模型总结一次。C 可在同一完整业务日内按证据改查时间窗、追踪请求或结束，沿用项目的模型和工具预算。B 的时间窗是本次基线的设定；换成全天或更强的 Java 调查规则需要重新测。
- 数据集在 `src/test/resources/eval/v1.8`。每组样本冻结在同一业务日结束时，避免使用切点之后的事实。订单与日志文件只给数据源，cases.json 的标准结论、引用条件和参考查源次数只给评分端。当前是 13 个合成回归场景，未做真实事故的分组留出和人工盲评。
- 判分使用事先约定的结论代号；同时支持生产 Java 快捷回答及缺证据保护语。只有 FINISHED 且结论命中才给正确分；还需引用与场景匹配的 POS 快照、目标请求的写库事实或对应源的查询回执，才给有据正确分。额外引用纳入引用条数。未识别的自由文本单列，无法由本指标直接判断其语义。
- 延迟从建案起算，含等待模型和查证。查源次数通过适配器实际调用计数（POS、美团和日志），不计本地证据读取。超参考次数是可复核的代理指标，不能据此认定每个超额调用都是多余调用。各组固定按 A、B、C 顺序运行；冷启动、网络波动和样本量都会影响 P95。
- 代表性反例：订单 810007 的网关在固定窗内，写库锁等待在窗外，C 可以沿请求找到它。810012 只有其他门店的事件，当前操作员查不到目标证据；查不到日志不能推出 POS 无单。每次改提示词或预算后都应重新运行三组并保留原报告。
- 工具和接口契约沿用仓库 README 与 `InvestigationTools.schemas()`。评测未新增对外 HTTP 接口或业务表迁移，只为合成适配器开放 classpath 夹具路径配置，默认继续使用原文件。运行评测不改变值班服务。历史报告保留在 results；生产部署回退沿用原 JAR 与原模式，在其各自的数据目录启动。
