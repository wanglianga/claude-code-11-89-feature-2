# 供水抢修调度服务（water-repair-service）

暴雨后的凌晨，值守人员从原始报修形成事故、核实隔离范围、派出合格班组并确认恢复。
本项目用 **Java 21 / Spring Boot 3.5 / PostgreSQL** 实现了一个真实可调用的后端服务，
覆盖：报修原文与归属判断、事故确认、图算法候选关阀组合与精确地址影响集合、
班组许可/当班窗口/车辆/工具校验、队伍与阀门操作许可的并发互斥、两种稀缺队伍调度路线、
五类现场回执的顺序约束、复压区间+校时容差核验、复压失败重新派工，压力复核推翻后的
范围/资源占用/通知全链路更正；以及**维修方案材料需求 → 仓库配料 → 现场扫码 → 竣工验收 → 召回追溯**
的完整材料闭环（两种配料粒度、批次冻结/召回、已用材料去向追溯）。

> 项目类型：**后端 API 服务**。自带 OpenAPI 3 文档与 Swagger UI，无独立前端页面。

---

## 1. 一分钟启动

### 方式 A：Docker Compose（一键，推荐验收）

```bash
docker compose up --build
```

- 自动启动 PostgreSQL 18 与应用，应用监听 <http://localhost:8080>
- 宿主机 PostgreSQL 端口冲突时：`DB_HOST_PORT=55432 APP_PORT=18080 docker compose up --build`
- 容器内已配置阿里云 Maven 镜像；样例数据在首次启动时自动播种

打开：

- Swagger UI：<http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 健康检查：<http://localhost:8080/api/health>

### 方式 B：本地开发启动（本工作区已验证）

环境：JDK 25（`/opt/jdk-25`，编译目标 21）、Maven 3.9（`/opt/maven`）、
PostgreSQL 18（本机 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`）。

```bash
# 1) 确保 PostgreSQL 可连（容器自带实例）
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev -c 'select 1;'

# 2) 开发模式运行（首次会自动建表+播种）
JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml spring-boot:run

# 或打包后运行
JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml -DskipTests package
/opt/jdk-25/bin/java -jar target/water-repair-service.jar
```

如需使用自己的数据库，用环境变量覆盖：

```bash
DB_URL='jdbc:postgresql://HOST:5432/DBNAME' DB_USER=u DB_PASSWORD=p \
  /opt/jdk-25/bin/java -jar target/water-repair-service.jar
```

---

## 2. 测试账号

| 用户名 | 密码 | 角色 | 用途 |
| --- | --- | --- | --- |
| `dispatcher` | `Disp@12345` | DISPATCHER | 值班调度员，全部写操作（受理/确认/派工/复核/关单/reset） |
| `crewsole` | `Crew@12345` | CREW（绑定 `C_SOLE`） | 唯一资质装备齐备的持证维修班班长，可提交回执 |
| `scout` | `Scout@12345` | CREW（绑定 `C_SCOUT`） | 勘查一班 |
| `warehouse` | `Ware@12345` | WAREHOUSE | 中心仓库材料员，配料/重做配料/发料/退回/召回/冻结/验收 |
| `viewer` | `View@12345` | VIEWER | 只读角色，写接口返回 403 |

登录换取 token：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatcher","password":"Disp@12345"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
echo "$TOKEN"
```

之后所有 `/api/**`（除 `/api/auth/login`、`/api/health`）都要带：

```
Authorization: Bearer <token>
```

在 Swagger UI 中点右上角 **Authorize**，粘贴 token 原文（不要带 `Bearer ` 前缀）即可调试。

---

## 3. 自动回放验证（推荐，纯 Python 标准库）

脚本会先登录、调用 `POST /api/admin/reset` 清库重播，然后真实发起并发 HTTP 请求，
逐步断言题目要求的全部场景。可重复执行。

```bash
# 终端 1：启动服务（见上）

# 终端 2：
python3 scripts/replay.py
# 或
bash scripts/verify.sh
# 远端/自定义地址：
BASE_URL=http://localhost:8080 python3 scripts/replay.py
```

预期结尾：

```
##############################################################################
# 回放断言结果：PASS=214  FAIL=0
# 🎉 全部场景断言通过：同段重拨 / 相邻双支管 / 并发互斥 / 复压重派 / 更正链 / 材料配料与召回追溯
ALL_DONE: true
```

`scripts/replay.py` 覆盖并核对：

> 材料/仓库场景位于第 15–20 节，使用 `warehouse/Ware@12345` 登录；样例工单日为 `2026-11-27`。

1. **同段重拨**：R1/R2 形成 I2 后，R3 热线重拨自动作为 `REDIAL` 来源挂入同一事故；
   事故数、派工数均不增加；重复追加来源幂等。
2. **相邻双支管**：相隔 <100m 的幸福路 8 号（A05→S2）与滨河巷 1 号（A06→S3）形成不同事故；
   路口地标与“幸福里 6 栋”同楼双地址都只给 `AMBIGUOUS [S2,S3]`，不自动合并；
   人工确认保留 v1 AUTO / v2 MANUAL 两级判断历史。
3. **精确影响集合**：单关 `V2 = A01..A05`；单关 `V1 = A01..A05 + A10..A13`（S1+S2，9 户）；
   `V3 = A06..A09`。I2 候选组合为 `[V2(5户), V1(9户)]`，I3 只有 `V3`，I1 只有 `V1`。
4. **资格门槛**：许可缺失（勘查班不能接维修单，422）、当班窗口外、
   车辆不可用、工具不可用都有逐项 `checks`。
5. **唯一持证班组两次并发派单**：两线程用 Barrier 同时请求 I2/I3 的维修派单，
   数据库行锁 + 时间窗重叠校验保证**恰好一个 200、一个 409 `CREW_ALREADY_BOOKED`**，
   409 细节给出占用方派工单/事故/窗口；取消后占用全部 `RELEASED` 且原因可查。
6. **两条调度路线**：
   - 路线 A（勘查先行，I3）：先派 `C_SCOUT`，04:25 到场确认 V3；
     跳序 `REPAIR` 被 `RECEIPT_ORDER_CONFLICT` 拒，勘查单提交 `ISOLATION` 被 422 拒；
     随后锁 `C_SOLE` 的 06:00-08:00 窗口被路线 B 的 I2 先占（409），
     最终改锁 09:30-11:30（与 I2 窗口 09:20 结束半开相接，不冲突）成功。
   - 路线 B（捆绑暂留，I2）：`holdOnly=true` 一次锁定
     `CREW C_SOLE + VALVE_PERMIT POP-V2 + VEHICLE VEH-1 + TOOL-*` 共 5 项 HELD 占用，
     派工单 `PROVISIONAL`，`issue` 后才 `DISPATCHED`。
7. **复压失败→重新派工**：I2 第一周期五回执齐全，`PR-S2-FAIL`（0.14MPa < S2 下限 0.22）
   核验 `FAILED`，关单 409 `REPRESSURE_NOT_PASSED`；`redispatch` 后
   旧派工单 5 项占用以 `REDISPATCH_AFTER_REPRESSURE_FAILURE` 释放，
   `cycleNo=2 / attemptNo=2 / parentAssignmentId` 成链；旧单再交回执报 `RECEIPT_WRONG_CYCLE`；
   新周期用 `PR-S2-OK` 通过并关单，新占用以 `INCIDENT_CLOSED` 释放。
8. **回执归属**：I3 的 `ARRIVAL` 永久归属勘查单/`C_SCOUT`，
   其余四条归属维修单/`C_SOLE`；每条回执都带 `assignmentId/assignmentCode/cycleNo`。
9. **压力复核更正链（I1）**：复核 `OVERTURNS` 把边界 S1/V1 改为 S2/V2：
   更正记录保存新旧边界/阀门/地址全集，`removed=[A10..A13]`、`added=[]`；
   占用侧释放 `POP-V1`（原因含 `PRESSURE_REVIEW_CORRECTION`）并新占 `POP-V2`；
   9 条原始通知全部 `SUPERSEDED` 且挂 `supersededByCorrectionId`，
   新发 5 条 `CORRECTION` + 4 条 `CORRECTION_CANCEL`（A10-A13），
   取消通知用 `replacesNotificationId` 链回旧通知——旧通知不删除、不伪装未发生。
10. **校时容差**：`POST /api/pressure/verify-repressure` 可分别判定
    “压力值超出支管区间”和“校时后采样时刻与复压时刻偏差超过容差”。
11. **材料需求与缺料**：维修方案把口径/材质/连接方式/额定压力传给仓库；需求 PN16 但仓库只有
    同系列 PN10 时返回 `409 MATERIAL_SHORTAGE` 并列出近似件的不符项，绝不用近似件顶替。
12. **两张维修单 + 方案变更**：M1 原估 DN100/PN16（BATCH 粒度获批即锁具体批次），现场确认一处为
    DN80/PN10 后下发方案 v2：旧配料单 `REPLACED`、旧件锁定释放，新配料单改为 DN100×1 + DN80/PN10×2。
    另用 CATEGORY 粒度跑同场景对照：获批时只锁参数类别（无具体批次），装车前才按当时合格库存挑批次。
13. **三种召回/变更顺序**：
    - *方案变更先到*：M1 方案 v2 释放旧件、重做配料（BATCH 两张配料单；CATEGORY 只改参数类别）。
    - *召回先到*：M4 配料后、发车前 DN100 密封件批次被召回 → 旧配料单释放并自动重做（旧单 REPLACED），发车批次不含召回件，
      风险清单无已领/已装记录。
    - *已领料后召回*：M2 已领未装 2 件，召回后 `issuableQty=0` 且拒绝再领用；已领未装件扫码退回（回库仍不可发）；
      仓库显式重做配料换用合格批次后才安装，验收依据只含新批次。
    - *已安装后召回*：M5 验收已把 Q4 关联到 S2；认证撤销后风险清单可定位 M5/ASG/S2/2 件/安装扫码/验收 id；
      用合格批次替换安装（旧安装保留并标 `remediatedByScanId`，归入“已整改”），二次验收以替换后的在役批次为准。
14. **冻结与不可发**：冻结批次 `issuableQty=0`、重复冻结 409、再领用被 `409 BATCH_NOT_ISSUABLE` 拒；
    冻结最末 PN16 批次后 PN16 需求缺料（不降级 PN10）。
15. **验收门槛**：已安装材料却未验收 → 关单 `409 MATERIAL_ACCEPTANCE_REQUIRED`；
    验收后又有替换安装 → 关单 `409 MATERIAL_REACCEPTANCE_REQUIRED`。
16. **恢复后旧批次核查**：所有 `issuableQty>0` 的批次必为 `QUALIFIED`；召回/冻结批次即使回库也保持 `issuableQty=0`。
17. 终态：所有 `resource_occupancy` 均为 `RELEASED`，全部事故/材料工单均 `CLOSED`。

---

## 4. 手工 API 走查（核心端点）

写接口按角色鉴权：调度类用 `dispatcher`，配料/发料/召回/冻结用 `warehouse`，
现场回执/扫码安装可用 `crewsole` 或 `dispatcher`。完整 schema 见 Swagger UI。

```bash
B=http://localhost:8080
H="Authorization: Bearer $TOKEN"; J='Content-Type: application/json'

# 复位样例（回放脚本开头也会调用）
curl -s -X POST $B/api/admin/reset -H "$H" -H "$J" -d '{}'

# 管网与精确影响
curl -s $B/api/network/addresses -H "$H" | python3 -m json.tool
curl -s -X POST $B/api/network/impact -H "$H" -H "$J" -d '{"valveCodes":["V2"]}'
curl -s -X POST $B/api/network/candidate-isolations -H "$H" -H "$J" \
  -d '{"boundarySegmentCodes":["S2"]}'

# 受理报修（自动归属判断）
curl -s -X POST $B/api/reports -H "$H" -H "$J" -d '{
  "callerPhone":"13900000009","rawAddress":"幸福路3号",
  "description":"低压","receivedAt":"2026-11-26T03:05:00+08:00"}'

# 调度确认事故
curl -s -X POST $B/api/incidents -H "$H" -H "$J" -d '{
  "title":"S2 低压","reportIds":[1],"boundarySegmentCodes":["S2"]}'

# 候选方案 → 选 V2 → 通知 A01-A05
curl -s $B/api/incidents/1/candidate-isolations -H "$H"
curl -s -X POST $B/api/incidents/1/isolation-plans -H "$H" -H "$J" -d '{"valveCodes":["V2"]}'
curl -s -X POST $B/api/incidents/1/notify -H "$H"

# 资格预检 → 捆绑暂留派工 → 下达
curl -s -X POST $B/api/incidents/1/dispatch/eligibility -H "$H" -H "$J" -d '{
  "kind":"REPAIR","crewCode":"C_SOLE",
  "windowStart":"2026-11-26T05:00:00+08:00","windowEnd":"2026-11-26T07:00:00+08:00"}'
curl -s -X POST $B/api/incidents/1/dispatch/repair -H "$H" -H "$J" -d '{
  "crewCode":"C_SOLE","strategy":"RESERVE_BUNDLE","holdOnly":true,
  "windowStart":"2026-11-26T05:00:00+08:00","windowEnd":"2026-11-26T07:00:00+08:00"}'

# 按顺序提交回执（assignmentId 从上一步响应取）
curl -s -X POST $B/api/incidents/1/receipts -H "$H" -H "$J" -d '{
  "assignmentId":1,"type":"ARRIVAL","eventAt":"2026-11-26T05:05:00+08:00","note":"到场"}'

# 事故全状态聚合（核对来源/判断/方案/通知/更正/派工/占用/回执/材料）
curl -s $B/api/incidents/1 -H "$H" | python3 -m json.tool

# ---------------- 材料：方案需求 → 配料 → 扫码 → 验收 → 召回 ----------------
WH=$(curl -s -X POST $B/api/auth/login -H "$J" \
  -d '{"username":"warehouse","password":"Ware@12345"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
WHH="Authorization: Bearer $WH"

curl -s $B/api/materials/batches -H "$WHH" | python3 -m json.tool   # issuableQty/paramKey

# 维修方案（口径/材质/连接/额定压力 → 仓库配料；BATCH 立即锁批次）
curl -s -X POST $B/api/incidents/1/repair-plans -H "$H" -H "$J" -d '{
 "granularity":"BATCH","approve":true,
 "lines":[{"lineNo":1,"category":"PIPE","dn":100,"material":"DUCTILE_IRON",
           "connectionType":"FLANGE","pn":16,"quantity":2,"segmentCode":"S2",
           "locationText":"幸福路3号阀后"}]}'

curl -s $B/api/incidents/1/material-provision -H "$WHH"      # 锁定批次/reveal
curl -s -X POST $B/api/incidents/1/material-scans/issue -H "$WHH" -H "$J" \
  -d '{"items":[{"batchNo":"PIPE-DN100-PN16-Q1","qty":1,"lineNo":1,"segmentCode":"S2"}]}'
# 安装扫码由现场班组/调度提交（仓库账号无安装权限）
curl -s -X POST $B/api/incidents/1/material-scans/install -H "$H" -H "$J" -d '{
 "item":{"batchNo":"PIPE-DN100-PN16-Q1","qty":1,"lineNo":1,"segmentCode":"S2","locationText":"S2 0+003"}}'

# 供应商召回：立即不可发，并返回在库/已领未装/已安装去向
curl -s -X POST $B/api/materials/recalls -H "$WHH" -H "$J" -d '{
 "batchNo":"SEAL-DN100-PN16-Q1","kind":"QUALITY_RECALL","reason":"同批复压鼓泡"}'
curl -s $B/api/materials/batches/SEAL-DN100-PN16-Q1/risk -H "$WHH" | python3 -m json.tool

# 竣工验收（批次×管段×数量×证书×安装扫码）
curl -s -X POST $B/api/incidents/1/material-acceptance -H "$H" -H "$J" -d '{}'
```

主要资源分组（Swagger Tag 与之一致）：

| 分组 | 代表端点 |
| --- | --- |
| 01 鉴权 | `POST /api/auth/login` |
| 02 管网图与影响 | `/api/network/*`、`POST /api/network/impact`、`/api/network/candidate-isolations` |
| 03 报修与归属 | `POST /api/reports`、`POST /api/reports/{id}/judgments` |
| 04 事故/方案/通知/复核 | `POST /api/incidents`、`/isolation-plans`、`/notify`、`/pressure-reviews`、`GET /{id}` |
| 05 派工与互斥 | `/dispatch/scout`、`/dispatch/repair`、`/redispatch`、`/api/assignments/{id}/cancel|complete|issue` |
| 06 回执与复压 | `POST /api/incidents/{id}/receipts`、`/close`、`POST /api/pressure/verify-repressure` |
| 07 材料与仓库 | `/api/incidents/{id}/repair-plans`、`/material-provision`、`/material-scans/*`、`/material-acceptance`；`/api/materials/batches`、`/api/materials/recalls` |
| 00 回放支持 | `POST /api/admin/reset` |

---

## 5. 领域模型与关键实现

### 5.1 随题样例管网（`src/main/resources/samples/network.json`）

```
NSRC ─ M0 ─ NT0 ─ F1 ─ NV1(V1) ─ F1D ─ NT1 ─ S1 ─ NE1   地址 A10-A13
                │                    └ F2 ─ NV2(V2) ─ S2 ─ NE2  地址 A01-A05
                └ F3 ─ NV3(V3) ─ S3 ─ NE3                      地址 A06-A09
```

- 节点：`SOURCE / TEE 三通 / VALVE 阀门 / END 末端`；管段连接两节点。
- 地址点唯一映射管段；`幸福里6栋`同时是 A04(S2)/A07(S3) 的别名，
  路口 `LM-XF-BH` 候选 `[S2,S3]`——用于证明“同楼/最近坐标 ≠ 同一事故”。
- 每段配置复压正常区间（如 S2 `[0.22, 0.45] MPa`）与校时容差（600s）。
- 压力读数带设备号、采样时刻 `sampledAt`、校时偏差 `clockOffsetSeconds`；
  约定 **校时后采样时刻 = sampledAt + clockOffsetSeconds**。

影响算法（`network/ImpactService.java`）：把关闭的阀门视为不可穿越节点，
从所有 SOURCE 做可达性遍历；一条管段两端都不可达即判为失压，其上地址全部受影响。
候选方案枚举全部阀门子集（≤4 阀组合），返回能隔离边界管段的“包含意义最小”组合。

### 5.2 归属与事故边界

- 每条报修永久保存原文（地址、描述、照片引用、接听时刻）。
- 自动归属按“最长别名命中 → 路口地标 → 坐标半径”的优先级产出
  `MATCHED / AMBIGUOUS / UNMATCHED` 判断；人工判断只追加新版本，不覆盖历史。
- 报修受理**不会**自动建事故；只有调度调用 `POST /api/incidents` 显式确认边界后才成案。
- 热线重拨带 `redialOfReportId/hotlineCallId`：若原线索已在事故中，
  新线索自动以 `REDIAL` 加入同一事故，不产生新事故或派工。

### 5.3 互斥与资格

- 资格逐项检查：班组 active、申请窗口落在当班时间（按 Asia/Shanghai）、
  所需资质（`Q_INSPECT/Q_REPAIR/Q_VALVE_STD/Q_VALVE_LARGE`，V1 要大阀许可）、
  绑定车辆 `available`、标配工具全部 `available`。
- 占用资源类型：`CREW / VALVE_PERMIT / VEHICLE / TOOL`。
- 预留时先对资源主行 `SELECT ... FOR UPDATE`，再用半开区间条件
  `existing.start < :end AND existing.end > :start AND status='HELD'` 查重叠。
  因此同一队伍（或同一阀门操作票）的两个并发派单在数据库层串行化，恰好一成一败；
  端点相接的窗口（如 09:20 结束 / 09:30 开始）不冲突。
- 释放不删行：占用行置 `RELEASED` 并写 `releaseReason/releasedAt`。

### 5.4 回执周期与更正链

- 顺序固定：`ARRIVAL → ISOLATION → REPAIR → FLUSH → REPRESSURE`，按 `(incident, cycleNo)` 成链。
- `ISOLATION` 及之后必须由 `REPAIR` 派工单提交；勘查单只能交 `ARRIVAL`。
- 复压必须引用压力读数，核验：压力值在该支管区间内、校时后采样时刻与回执时刻偏差 ≤ 容差、
  读数设备管段与当前边界一致；任一不过即 `FAILED`，`/close` 返回 409。
- `redispatch` 释放上一维修单占用、`cycleNo+1`、建立新 `Assignment(attemptNo,parent)`；
  旧周期回执与失败结论原样保留，旧单不能再写新周期。
- 压力复核 `OVERTURNS` 在一个事务内完成：旧方案 `CORRECTED` + 新方案版本、
  事故边界更新、地址集合 diff、阀门操作票“释放旧票/新占新票”（新票冲突则整体回滚）、
  旧通知 `SUPERSEDED` + 更正/取消通知链、`CorrectionRecord` 留全量快照。

### 5.5 材料：需求 → 配料 → 扫码 → 验收 → 召回追溯（`material/`）

随题样例仓库：`src/main/resources/samples/materials.json`（合格批次库存与供应商台账）。
两张维修单与召回数据：`src/main/resources/samples/material-repair-orders.json`
（M1 现场改口径、M2 已领料后召回、M4 召回先到、M5 已安装后认证撤销、缺料探针）。

**需求来源**：`POST /api/incidents/{id}/repair-plans` 把维修方案计算出的
`category/dn/material/connectionType/pn/quantity/segmentCode`（安装管段）作为需求行传给仓库。
每次现场改口径/改材质都新增方案版本；旧方案 `SUPERSEDED`，旧配料单释放并保留。

**等价判定（硬约束）**：口径、材质、连接方式必须完全一致，额定压力只允许“不低于需求”。
因此 `DN100/PN10` 不是 `DN100/PN16` 的等价件——缺料返回 `409 MATERIAL_SHORTAGE`，
`details.nearMatches` 列出所有同系列近似件及其不符项（含“PN10 低于需求 PN16”），
**绝不自动顶替**。

**两种配料粒度**（`granularity`）：

| 粒度 | 何时锁定 | 换料/退回 | 适用 |
| --- | --- | --- | --- |
| `BATCH` | 方案获批即锁具体合格批次（可 `preferredBatchNo` 指定），扣减 `quantityLocked` | 方案变更/批次召回 → 释放旧件（未发部分退回可用量）、旧配料 `REPLACED`，重做新配料 | 发车时物料确定、追责直接 |
| `CATEGORY` | 获批时只校验“满足参数的材料类别”有货，不落批次 | 装车前（派工/暂缓单下达）按**当时**合格库存挑具体批次；发料时再重验数量、证书、批次状态 | 现场变更更灵活 |

**现场扫码**（`/material-scans/{issue|return|install}`）：领用按具体批次、记录 `segmentCode` 安装位置；
退回回补 `quantityOnHand`；安装可带 `replacesScanId` 以合格批次替换旧安装，旧安装保留并回填
`remediatedByScanId`（归入“已整改”，历史不删除）。领用时若批次 `FROZEN/RECALLED/证书失效`，
直接 `409 BATCH_NOT_ISSUABLE`。

**批次状态机**：`QUALIFIED → FROZEN`（仓库冻结）/ `RECALLED`（供应商质量召回或饮用水认证撤销，
`CERT_REVOCATION` 同时置 `certStatus=REVOKED`）。`issuableQty = qualified ? onHand - locked : 0`；
召回/冻结批次即使退回回库也保持 `issuableQty=0`。冻结/召回会 `BLOCK` 相关分配、退回未发锁定量；
未领料的 BATCH 配料自动重做；已领/已装的保留扫码历史并置 `NEEDS_REPLACEMENT`，交现场退回/替换。

**召回风险清单** `GET /api/materials/batches/{batchNo}/risk`（或召回接口直接返回）：
- `inWarehouse`：仍在库数量、已锁定、已退回、`issuableQty`（召回后为 0）、状态与原因；
- `issuedNotInstalled`：已领未装（工单/派工单/班组/管段/位置/数量）；
- `installed` / `remediated`：已安装（管段、位置、安装扫码、验收 id、证书）与已被替换整改的旧安装。

**竣工验收** `POST /api/incidents/{id}/material-acceptance`：把当前有效安装扫码的批次按
管段/位置/数量/证书写成 `AcceptanceItem`，即“验收依据”，供召回后反查“已安装材料的具体去向”。
有安装却未验收 → 关单 `409 MATERIAL_ACCEPTANCE_REQUIRED`；验收后又发生安装/替换 →
`409 MATERIAL_REACCEPTANCE_REQUIRED`（需重新验收）。

**三种顺序 × 两种粒度对比**（回放第 15–20b 节，逐步断言）：

| 场景 | BATCH 粒度 | CATEGORY 粒度 |
| --- | --- | --- |
| 方案变更先到（M1：DN100→DN80/PN10） | 旧配料 `REPLACED`、旧件锁定退回；v2 重锁 DN100×1 + DN80/PN10×2 | v1 无具体批次、无需退回；v2 只改参数类别，装车时挑批次 |
| 召回先到（M4） | 未领料 → 释放旧配料并自动重做，发车批次不含召回件 | 召回作废已挑批次，置 `NEEDS_REPLACEMENT`，装车前重挑 |
| 已领料后召回（M2） | 已领未装退回（回库仍不可发）→ 仓库显式重做 → 安装合格批次 | 发料时重验会选中当时合格批次，旧批次拒绝出库 |
| 已安装后召回（M5） | 已装去向可查（工单/管段/验收 id）→ 合格批次替换安装 → 二次验收 | 同上；替换后旧安装归入“已整改” |

---

## 6. 本地开发与验证方式（实际执行记录）

以下验证均在本工作区容器内**实际执行通过**（日期 2026-11-25）：

| 验证 | 命令 | 实际结果 |
| --- | --- | --- |
| 编译 | `JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml compile` | BUILD SUCCESS |
| 单元测试 | `... mvn -s settings.xml test` | `ImpactServiceTest` 4 项 + `MaterialEquivalenceTest` 4 项，Tests run: 8, Failures: 0, Errors: 0 |
| 打包 | `... mvn -s settings.xml package` | 生成 `target/water-repair-service.jar`（约 57 MB fat jar，测试全过） |
| 真实运行 | `/opt/jdk-25/bin/java -jar target/water-repair-service.jar` | 8080 启动成功，Hibernate 连 PostgreSQL 18.1 建表/播种，日志无 ERROR |
| 端到端回放 | `python3 scripts/replay.py` | `PASS=214 FAIL=0`，连续重复执行结果一致（含真实双线程并发派单与材料召回场景） |
| 接口文档 | `GET /v3/api-docs`、`/swagger-ui/index.html` | 均 200，OpenAPI 含全部 `/api/**` 路径（含材料/仓库端点） |

说明与限制（如实记录）：

- 当前容器**未安装 Docker**，因此 `docker compose up --build` 未能在本机实际执行；
  `Dockerfile`（多阶段 Maven 21 构建 + JRE 21 运行）与 `docker-compose.yml`
  （`postgres:18-alpine` + 健康检查 + 依赖编排）已按标准方式提供，需在有 Docker 的环境验收。
- 回放使用显式 ISO 时间（抢修样例事故日 `2026-11-26`、材料样例工单日 `2026-11-27`，时区 `+08:00`），不依赖系统时钟，可重复。
- 材料批次的证书有效期按“当天”判定（样例证书 2027–2029 年到期，回放日 2026-11-27 均有效）。
- 短信通道为 `SIMULATED_SMS`：通知落库并在接口/聚合详情中可查，不接真实短信网关。

---

## 7. 目录速览

```
pom.xml / settings.xml / Dockerfile / docker-compose.yml
scripts/replay.py                 # 214 断言的端到端回放（纯标准库）
src/main/resources/application.yml
src/main/resources/samples/       # 随题提供的管网/班组/账号/报修/回执/材料批次/维修单样本
src/main/java/com/waterrepair/
  auth/        账号、登录 token、角色拦截
  common/      统一异常、健康检查、首页跳转
  config/      Web/OpenAPI/播种/reset
  network/     节点/管段/阀门/地址/压力读数 + 图影响算法
  report/      报修原文 + 归属判断版本
  incident/    事故/来源/方案/通知/更正/压力复核
  crew/        班组/车辆/工具/阀门操作许可
  dispatch/    派工单 + 资源占用 + 行锁互斥 + 资格校验
  receipt/     五类顺序回执 + 复压核验
  material/    维修方案材料需求/配料(两种粒度)/扫码/验收/冻结召回与风险清单
src/test/java/com/waterrepair/network/ImpactServiceTest.java
src/test/java/com/waterrepair/material/MaterialEquivalenceTest.java
```
