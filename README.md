# 美团订单回传 POS 排障 · V1.7 第一阶段

## 本机 MySQL 只读查单

`pos-shadow` 模式供可跨店查单的本机运维按订单号核实 POS。返回来源、查询时间、支付与销售流水是否存在。两个存在标志来自同一条参数化 SELECT。两者均存在为 `FOUND`，均不存在为 `NOT_FOUND`，仅一者存在为 `PARTIAL`，需要人工核对。连接错误、授权失败或超时返回 5xx，两个存在标志均为空。当前接入范围是 `127.0.0.1:3306/mtjd` 的 `t_rm_payflow_frommail.mail_orderid` 和 `t_rm_saleflow_frommail.mail_orderid`。

在 PowerShell 中运行以下命令。令牌在当前会话生成，数据库口令在提示符输入。先用项目脚本完成打包。

~~~powershell
.\project-mvn.cmd package
$env:POS_DB_PASSWORD = [Net.NetworkCredential]::new('',(Read-Host '本机 MySQL 只读口令' -AsSecureString)).Password
$env:POS_SHADOW_TOKEN = [guid]::NewGuid().ToString('N')
$app = Start-Process 'F:\ayd\order-project-jdk21\jdk-21.0.13+9\bin\java.exe' -ArgumentList '-jar','target/order-investigator-1.7.0-pos.1.jar','--spring.profiles.active=pos-shadow' -WindowStyle Hidden -PassThru
~~~

服务启动后在同一 PowerShell 中查询，结束时使用 `Stop-Process -Id $app.Id`。

~~~powershell
Invoke-RestMethod 'http://127.0.0.1:8081/pos-shadow/orders/check' -Method Post -Headers @{Authorization="Bearer $env:POS_SHADOW_TOKEN"} -ContentType 'application/json' -Body '{"orderId":"123456"}'
~~~

此模式独立于下方沙箱，真实订单号不会进入演示案件、模型上下文或补单执行器。门店归属待接入美团详情的 `app_poi_code`；当前接口适用于已获跨店读权限的本机运维。下方示例使用默认沙箱模式。

值班运维用一个美团订单号创建查证案件。Java 先查 POS，仅在 POS 明确返回无记录时查询美团交易状态。每次创建保存一份观测快照；读取同一案件可复核当时的结果，重新查单请创建新案件。当前使用版本化合成订单、本机演示身份和 H2 文件库。

## 启动与验收

项目使用 Eclipse Temurin JDK 21，编译目标为 Java 21。本机 JDK 位于 `F:\ayd\order-project-jdk21\jdk-21.0.13+9`。Windows 下从项目根目录运行：

~~~powershell
.\project-mvn.cmd package
.\project-mvn.cmd spring-boot:run
~~~

`project-mvn.cmd` 通过 `setlocal` 将 JDK 选择限定在本次 Maven 进程及其子进程。用 `.\project-mvn.cmd -version` 核对 Java 版本与路径。若直接运行已打包的 JAR，在 PowerShell 使用 `& 'F:\ayd\order-project-jdk21\jdk-21.0.13+9\bin\java.exe' -jar target/order-investigator-1.7.0-pos.1.jar`。

默认监听 127.0.0.1:8080。案件库在工作目录 data 下，重启时使用同一工作目录。Maven 测试使用独立内存库。演示令牌用于本机模拟门店身份，实际运维身份系统留待真实只读接入阶段。

PowerShell 创建并读取一张模拟案件：

~~~powershell
$headers = @{ Authorization = 'Bearer demo-ops-a' }
$case = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/cases' -Headers $headers -ContentType 'application/json' -Body '{"orderId":"123456"}'
$case | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri ("http://127.0.0.1:8080/cases/" + $case.id) -Headers $headers
~~~

POST /cases 返回 201 和 Location，GET /cases/{id} 返回 200。输出含门店、创建人、目标、结论及按查询顺序排列的观测，每项带来源、订单、范围和观测时间。认证失败返回 401，无查单角色返回 403；订单归属未知及跨店请求统一返回 404。演示身份 demo-ops-a 对应 033101，demo-ops-b 对应 033102，demo-viewer 无查单权限。订单号按字符串传递，以保留前导零。

## V1.6 在隔离沙箱验证补单结果

默认模式使用持久化的本地模拟 POS。对下面示例中的申请执行一次本人确认，即可触发沙箱同步补单与 POS 回查。再次用 `123456` 查单时，案件和 Agent 也能读到沙箱的新状态。每轮独立演示可使用新的 `--repair.fixture-environment=demo2` 启动参数。沙箱只处理 `sandbox:<环境名>` 的申请，V1.5 留存的 `fixture:` 申请不会被恢复任务消费。

| 确认接口返回 | 值班员如何判断 |
|---|---|
| `VERIFIED`，`execution.posResult=FOUND` | 回查时 POS 已有单。若 `callResult=SKIPPED`，则订单在执行前已到达。 |
| `NEEDS_VERIFICATION` | 将 `callResult` 和 `posResult` 一起查看。例如 `DUPLICATE / NOT_FOUND` 表示判重与查单结果矛盾，由值班员复核。 |
| `BLOCKED` | 查看 `precondition`，例如已有另一张申请正在处理同一订单。 |

同店值班员可用 `POST /cases/{caseId}/repair-attempts/{id}/verify` 再查待核验订单。每次核验都会留下操作者和 POS 观测。服务重启时，有执行记录的未决订单只做只读对账。若在记录“执行中”之后、发出请求之前断电，也保留待核验状态。案件的 `historicalInvestigations` 地址可查看历史原因的调查证据；订单恢复本身无法证明首次回传为何失败。

默认沙箱场景为 `NORMAL`。用 JAR 启动参数 `--repair.sandbox-response=LOST_REPLY` 可演示写入后超时，`DUPLICATE_WITHOUT_POS` 可演示重复提交但查不到单，`TIMEOUT_WITHOUT_WRITE` 可演示超时且仍无单。每次切换场景建议使用独立的演示环境名。以上流程无需模型密钥。

## V1.5 查看补单资格、申请和本人确认

案件的 `actions` 给出三个入口。GET `checkStatus` 回源检查业务条件，POST `investigate` 进入 Agent 调查，POST `requestRepair` 创建本单申请。对 `requestRepair` 做 GET 可以查看同案申请、两次核查的证据、操作者、确认时间及失效原因。授权值班人可独立完成整个流程。

接上面的模拟订单 `123456`，先看申请内容。

~~~powershell
$origin = 'http://127.0.0.1:8080'
Invoke-RestMethod ($origin + $case.actions.checkStatus) -Headers $headers
$attempts = $origin + $case.actions.requestRepair
$a = Invoke-RestMethod $attempts -Method Post -Headers $headers
$a | ConvertTo-Json -Depth 6
~~~

核对渠道、订单、处置动作、POS 与美团观测以及有效期后，由申请人提交刚审核过的目标。

~~~powershell
$reviewed = $a.target | ConvertTo-Json
Invoke-RestMethod "$attempts/$($a.id)/confirm" -Method Post -Headers $headers -ContentType 'application/json' -Body $reviewed
Invoke-RestMethod "$attempts/$($a.id)" -Headers $headers | ConvertTo-Json -Depth 6
~~~

申请初态为 `AWAITING_CONFIRMATION`。确认时 Java 再查订单归属、门店动作权限、有效期和当前业务状态。在兼容的只确认模式（`repair.sandbox-enabled=false`）下，通过后停在 `PENDING_EXECUTION`。双击确认返回同一条记录。默认确认窗口为 10 分钟，可配置 `repair.confirmation-ttl`。只确认模式的记录标记为 `fixture:<环境名>`，默认 `fixture:local`，自动化用例使用独立库。读写接口按环境过滤；不同演示部署共库时请分别设置 `repair.fixture-environment`。

换用 `123457` 创建案件可演示历史与当前状态的区别。即使首次回传失败，当前 POS 已有单，创建补单申请返回 HTTP 409 和 `POS_PRESENT`。若申请后原单才到、POS 超时、美团交易状态改变、申请过期或归属变化，确认返回 409，详情保留 `INVALIDATED` 和原因。另一位同店运维可以查看申请，代为确认返回 403。申请人的补单权限被撤销后再次确认也返回 403。

用“那这单现在需要补吗？”追问已有调查，模型可选择只读工具 `checkRepairEligibility`。该工具返回本次业务观测、Java 规则结论及适用的申请地址。运维在工单接口完成申请和确认；聊天消息只进入调查轨迹。这两个操作在模型离线时也可独立完成。

## V1.3 用 qwen-flash 调查一张订单

POS 与美团查单从 `fixtures/v1.1/orders.json` 读取合成状态，日志适配器检索 `fixtures/v1.2/logs.json` 的脱敏操作摘要；案件、证据、权限校验、运行轨迹和 H2 持久化均由应用实际执行。自动化测试用脚本化模型控制分支。只有调用调查入口并配置密钥时才会发起真实百炼推理请求，普通查单继续走 Java 规则。

在启动应用的 PowerShell 会话中输入密钥。进程结束后可用 `Remove-Item Env:DASHSCOPE_API_KEY` 清除此会话的变量。

~~~powershell
$secret = Read-Host '百炼 API Key' -AsSecureString
$env:DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
.\project-mvn.cmd spring-boot:run
~~~

默认使用北京地域兼容端点。如需切换至实际业务空间，可在启动前设置 `DASHSCOPE_BASE_URL` 为官方工作空间的 HTTPS 地址，路径截至 `/compatible-mode/v1`。[百炼工具调用文档](https://help.aliyun.com/zh/model-studio/qwen-function-calling)

另开 PowerShell，创建一个新案件，再发起单次调查：

~~~powershell
$api = 'http://127.0.0.1:8080/cases'
$h = @{ Authorization = 'Bearer demo-ops-a' }
$case = Invoke-RestMethod $api -Method Post -Headers $h -ContentType 'application/json' -Body '{"orderId":"123457"}'
$question = @{ question='这笔订单第一次回传到底发生了什么？请定位原始接收请求，再核实对应请求的处理记录。' } | ConvertTo-Json
$run = Invoke-RestMethod "$api/$($case.id)/investigations" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body $question -TimeoutSec 120
$run | ConvertTo-Json -Depth 8
~~~

模型可查询 POS、美团、按时段查日志，或从本轮已见证据追踪请求。Java 绑定案件范围、校验参数与引用，默认每次调查最多调用 6 次只读工具、8 次模型，时间预算 90 秒。工具返回与收集的证据会回传给模型，由模型决定继续、结束或请求澄清。结果分为 FINISHED、NEEDS_INPUT、HANDOFF；FINISHED 表示完成调查答复，其中可以包含明确的证据缺口。模型超时、不可用、重复动作或预算用尽时保留步骤并转人工。

通过 `GET /cases/{caseId}/investigations/{id}` 读取步骤、操作者、参数指纹、耗时、Token 和证据 ID；沿 `GET /cases/{caseId}/evidence/{id}` 核实引用。用 `123456` 可演示仅查到接收记录、缺少同次写库日志的情形。引用校验验证证据归属，结论的语义正确性仍需用例或运维复核。

## V1.4 在同一案件中接班、恢复和追问

**恢复**继续同一调查 ID，沿用预算和已提交步骤。**追问**以已结束的调查为父调查，保存新问题，分配新调查 ID 与预算。案件的完整证据仍可查；构造追问上下文时读取上一轮问题、答复及同案日志，优先保留目标请求，最多带入 12 条历史日志。例如查“后来补单”只带入已识别的补单请求日志，其事件时间与原始证据 ID 都会保留。涉及当前 POS 状态时，建议显式使用 `goal: CURRENT_POS`；服务端直接回源。默认 AUTO 支持“现在 POS 有单吗”等常见问法；复杂历史问题可显式用 HISTORY。

以下命令接续上一节已启动的应用和创建的案件。先提交一个新调查，仅执行一步。可在两步之间重启服务，使用相同工作目录和数据库。

~~~powershell
$runs = "$api/$($case.id)/investigations"
$body = @{question='第一次回传为什么失败？先查网关接收记录。';stepLimit=1} | ConvertTo-Json
$partial = Invoke-RestMethod $runs -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body $body
$next = @{ Authorization='Bearer demo-ops-a-next' }
$thread = "$runs/$($partial.id)"
$first = Invoke-RestMethod "$thread/resume" -Method Post -Headers $next -ContentType 'application/json' -Body '{}'
$body = @{question='那后来补进去的那次呢？'} | ConvertTo-Json
$repair = Invoke-RestMethod "$thread/follow-ups" -Method Post -Headers $next -ContentType 'application/json; charset=utf-8' -Body $body
$repair.steps | Format-Table number,operator,action,result,evidenceIds
~~~

|操作|结果|
|---|---|
|GET /cases/{caseId}/investigations|列出本案各轮问题、父调查、状态、最后领取人和检查点版本，可找回中断的调查 ID|
|创建或追问携带 stepLimit|0 只建档，1–8 为本次请求最多执行的步数，省略时尽量执行到结束；已提交但还有工作的调查显示 PAUSED|
|POST /cases/{caseId}/investigations/{id}/resume|按当前值班身份复查门店授权；恢复时丢弃易变快照，保留历史日志与剩余预算。终态直接返回原报告|
|POST /cases/{caseId}/investigations/{id}/follow-ups|从 FINISHED、NEEDS_INPUT 或 HANDOFF 开始新一轮；未完成的父调查返回 409。切换订单需进入对应案件|

每轮模型请求前由数据库锁定版本并预留时间。模型等待期间不占数据库事务；工具执行、证据、步骤、状态和检查点在一个事务内提交。领取中的同一步返回 409。若进程在等待模型时退出，未提交的轮次及其预留时间按已消费处理；租约最长约 35 秒，到期后可接手。运维暂停或跨班等待不消耗主动执行时间。V1.3 历史报告可读、终态可追问；旧版运行中案件缺少检查点，需要新建调查。

权限仍为本地演示实现：demo-ops-a-next 与 demo-ops-a 同属 033101。服务端按 `demo_operator_grant` 复核授权；停用旧值班身份后，原令牌不能恢复调查。调查接口始终只读。

运行 `.\project-mvn.cmd -Dtest=RestartCheckpointTest test` 可在离线环境复验跨进程恢复。测试启动两个独立 JVM，在首步提交后强制终止第一个进程，由第二个进程读取同一 H2 文件；模型为脚本化替身。结果写入 `target/v1.4-restart-replay.json`。

已保存一次[真实 qwen-flash 跨进程验收轨迹](examples/v1.4/qwen-flash-restart.json)，使用合成订单 123457。首班运行一次 searchOrderLogs 后终止进程；接班人重启后继续 followLogReference 并确认首次处理因门店映射缺失而终止。追问后来补单时只有 followLogReference 和 finish 两步，沿已有 REPAIR-02 证据读取处理记录；问当前状态时只有 queryPosOrder 一步。JSON 中可逐项核对步骤号、操作者及证据 ID。

## V1.2 演示：查 123457 首次回传与后来补单

以下均为合成记录，时间采用北京时间。单号 `123457` 目前在 POS 有单。先看网关的两条接收记录，再通过证据 ID 查询同一次请求在写库项目里的记录。

~~~powershell
$headers = @{ Authorization = 'Bearer demo-ops-a' }
$api = 'http://127.0.0.1:8080/cases'
$case = Invoke-RestMethod -Method Post -Uri $api -Headers $headers -ContentType 'application/json' -Body '{"orderId":"123457"}'
$base = "$api/$($case.id)"
$q = @{ source='GATEWAY'; from='2026-11-18T10:00:00+08:00'; until='2026-11-18T11:00:00+08:00' }
$gateway = Invoke-RestMethod -Method Post -Uri "$base/logs/search" -Headers $headers -ContentType 'application/json' -Body ($q | ConvertTo-Json)
$q.source = 'POS_WRITER'
foreach ($event in $gateway.events) {
    $result = Invoke-RestMethod -Method Post -Uri "$base/evidence/$($event.id)/follow" -Headers $headers -ContentType 'application/json' -Body ($q | ConvertTo-Json)
    $result.events | Select-Object requestRef,eventTime,excerpt,id
}
(Invoke-RestMethod -Uri $base -Headers $headers).evidence | Format-Table kind,source,requestRef,eventTime,excerpt
~~~

|北京时间|请求|写库日志中的观测|
|---|---|---|
|10:02:03|PUSH-01|门店映射缺失，本次处理终止|
|10:21:02|REPAIR-02|补单写入 POS 成功|

把订单换为 `123456`，保持相同的查询窗口。它也有一条 PUSH-01 接收记录，追踪该单的写库日志会得到 `NO_MATCH`；现有证据只支持“已接收”。单号 `123458` 的 POS_WRITER 源模拟超时。

|案件下的接口|用途|
|---|---|
|GET /log-tools|查看当前启用的工具、允许的日志源和查询范围|
|POST /logs/search|按当前案件、单个来源和时间窗查日志，可指定 limit|
|POST /evidence/{id}/follow|提交同样的查询参数，从本案件已有日志证据取得请求标识；订单和门店由服务端固定|
|GET /evidence/{id}|读回采集时的脱敏快照或查询回执|

时间窗左闭右开，单次最多 24 小时、50 条，由 `scenarios/meituan-orders-v1.2.properties` 配置。默认启用 GATEWAY 和 POS_WRITER。配置 `order-logs.follow-enabled=false` 后，工具列表仅提供按单搜索。日志合成数据保留起点固定为 2026-11-01T00:00:00Z，方便以后重放。

搜索返回 `MATCHES`、`NO_MATCH`、`TIMEOUT` 或 `EXPIRED`。只要窗口含过期部分，`expired` 即为 true，返回结果只覆盖可查询时段；`truncated=true` 时可缩小窗口。每次查询留下独立 SEARCH 回执。相同案件内重复采集同一日志事件复用原证据 ID。LOG 记录含事件时间；POS、美团的 SNAPSHOT 仅提供实际观测时间，未提供的业务事件时间留空。案件详情保留 V1.1 的 observations，同时返回按时间排列的 evidence。历史案件首次读回时根据原始快照补齐证据。

模拟日志源仅投影夹具中已脱敏的 safeMessage 和查询所需字段，privateFields 不进入证据或响应。当前工具由运维手动调用，日志调查不会执行补单。

## 合成基线结果与人工待查项

下表为通过 HTTP 创建并读回案件的参数化测试样本。生产夹具只含模拟工具的可见输入；业务真相及预期结论保存在测试资源。所有样本均为合成数据。

|订单号|规则结论|美团查询次数|运维如何使用|
|---|---|---:|---|
|123456|MISSING_CANDIDATE|1|POS 明确无单且美团已完成。可调查历史原因，或进入 V1.6 沙箱申请流程|
|123457、223456|POS_PRESENT|0|已确认入库。第二单使用门店 B 身份；历史失败原因仍需另查|
|123458|POS_TIMEOUT|0|人工核实 POS 可用性，不能确认是否缺单|
|123459|POS_DENIED|0|核对 POS 源访问权限|
|123460|MEITUAN_NOT_COMPLETED|1|交易尚未完成，当前不符合完成单口径|
|123461|MEITUAN_NOT_FOUND|1|本店归属可确定，美团未返回订单，核对渠道记录|
|123462|MEITUAN_TIMEOUT|1|渠道查询超时，待恢复后复核|
|123463|MEITUAN_DENIED|1|核对美团源访问权限|

基线判定在 RuleBaseline，模拟源通过 OrderSources 接入。业务范围从演示身份和服务端订单归属取得。夹具目录为 src/main/resources/fixtures/v1.1，评测预期为 src/test/resources/baseline/v1.1/expected.csv。运行 `.\project-mvn.cmd test` 可复测 9 笔订单以及角色、跨店、案件读回和查询短路。
