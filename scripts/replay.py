#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
供水抢修服务端到端回放脚本（仅依赖 Python 标准库）。

回放题目要求的四条主线：
  1. 同段重拨：热线重拨只添来源，不增事故/派工；
  2. 相邻双支管：相邻报修分别归属 S2 / S3，候选边界与影响集合精确；
  3. 唯一持证班组被两次并发派单：行锁+时间窗互斥，恰好一成一败，并验证占用释放；
  4. 复压失败后重新派工：顺序回执、复压门槛、周期封存、占用释放、历史回执归属。
另含：两种调度路线（勘查先行 / 维修队+阀票窗口捆绑暂留）、
      压力复核推翻后的范围/占用/通知更正链、资质/当班/车辆/工具门槛、校时容差试算。

用法：
  python3 scripts/replay.py                # 默认 http://127.0.0.1:8080
  BASE_URL=http://127.0.0.1:8080 python3 scripts/replay.py
退出码 0 表示全部断言通过。
"""
import json
import os
import sys
import time
import threading
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080").rstrip("/")
ROOT = Path(__file__).resolve().parent.parent
SAMPLE_DIR = ROOT / "src" / "main" / "resources" / "samples"

PASS_COUNT = 0
FAIL_COUNT = 0
FAIL_LINES = []


def section(title):
    print("\n" + "=" * 78)
    print("▶ " + title)
    print("=" * 78)


def check(name, cond, detail=""):
    global PASS_COUNT, FAIL_COUNT
    if cond:
        PASS_COUNT += 1
        print(f"  ✅ {name}")
    else:
        FAIL_COUNT += 1
        FAIL_LINES.append(f"{name} :: {detail}")
        print(f"  ❌ {name}  {('- ' + str(detail)) if detail else ''}")


def require(name, cond, detail=""):
    check(name, cond, detail)
    if not cond:
        raise AssertionError(f"关键断言失败，回放中止: {name} {detail}")


# ---------------- HTTP 客户端 ----------------

def http(method, path, body=None, token=None, expect=None):
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            status = resp.status
            payload = json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"raw": raw}
        status = e.code
    if expect is not None and status != expect:
        raise AssertionError(f"{method} {path} 期望 {expect}，实际 {status}: {payload}")
    return status, payload


def wait_healthy(timeout_sec=90):
    deadline = time.time() + timeout_sec
    last = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/api/health", timeout=5) as r:
                if r.status == 200:
                    return True
        except Exception as e:  # noqa
            last = e
            time.sleep(2)
    print("服务未就绪:", last)
    return False


def login(username, password):
    _, data = http("POST", "/api/auth/login",
                   {"username": username, "password": password}, expect=200)
    return data["token"]


def as_set(xs):
    return set(xs or [])


# ---------------- 主流程 ----------------

def main():
    if not wait_healthy():
        print("ALL_DONE: false")
        sys.exit(2)

    reports_doc = json.loads((SAMPLE_DIR / "reports.json").read_text(encoding="utf-8"))
    orders = json.loads((SAMPLE_DIR / "material-repair-orders.json").read_text(encoding="utf-8"))
    receipt_tpls = json.loads(
        (SAMPLE_DIR / "field-receipt-templates.json").read_text(encoding="utf-8"))["templates"]

    # ---------- 0. 鉴权与 reset ----------
    section("0. 鉴权、测试账号与可重复播种")
    st, _ = http("GET", "/api/incidents")
    check("未登录访问 /api/incidents 返回 401", st == 401, f"status={st}")

    viewer_token = login("viewer", "View@12345")
    st, body = http("POST", "/api/incidents", {"reportIds": [1]}, token=viewer_token)
    check("VIEWER 账号不能调度确认事故（403）", st == 403, f"status={st} body={body}")

    disp = login("dispatcher", "Disp@12345")
    crew_token = login("crewsole", "Crew@12345")
    check("四个测试账号均可登录（dispatcher/viewer/crewsole）",
          bool(disp and viewer_token and crew_token))

    st, reset_body = http("POST", "/api/admin/reset", {}, token=disp, expect=200)
    check("POST /api/admin/reset 重新播种成功", reset_body.get("reset") is True, reset_body)

    st, doc = http("GET", "/v3/api-docs")
    check("OpenAPI 3.x 文档 /v3/api-docs 可匿名访问且含接口定义",
          st == 200 and "/api/incidents" in json.dumps(doc), f"status={st}")
    check("OpenAPI 含材料/仓库接口（配料/扫码/召回/验收）",
          "/api/incidents/{id}/repair-plans" in json.dumps(doc)
          and "/api/materials/batches" in json.dumps(doc)
          and "/api/materials/recalls" in json.dumps(doc), "material paths missing")
    try:
        with urllib.request.urlopen(BASE + "/swagger-ui/index.html", timeout=10) as r:
            check("Swagger UI /swagger-ui/index.html 可访问", r.status == 200)
    except Exception as e:  # noqa
        check("Swagger UI /swagger-ui/index.html 可访问", False, str(e))

    # ---------- 1. 样例管网与精确影响集合 ----------
    section("1. 样例管网：S1=A10-A13, S2=A01-A05, S3=A06-A09；V1/V2 影响集合")
    _, addresses = http("GET", "/api/network/addresses", token=disp)
    seg_of = {a["code"]: a["segmentCode"] for a in addresses}
    s1 = sorted(c for c, s in seg_of.items() if s == "S1")
    s2 = sorted(c for c, s in seg_of.items() if s == "S2")
    s3 = sorted(c for c, s in seg_of.items() if s == "S3")
    check("S1 地址恰为 A10-A13", s1 == ["A10", "A11", "A12", "A13"], s1)
    check("S2 地址恰为 A01-A05", s2 == ["A01", "A02", "A03", "A04", "A05"], s2)
    check("S3 地址恰为 A06-A09", s3 == ["A06", "A07", "A08", "A09"], s3)

    _, imp_v2 = http("POST", "/api/network/impact", {"valveCodes": ["V2"]}, token=disp)
    check("单关 V2 影响集合精确为 A01-A05",
          imp_v2["affectedAddressCodes"] == ["A01", "A02", "A03", "A04", "A05"],
          imp_v2["affectedAddressCodes"])

    _, imp_v1 = http("POST", "/api/network/impact", {"valveCodes": ["V1"]}, token=disp)
    check("单关 V1 同时影响 S1 与 S2（A01-A05 + A10-A13，共 9 户）",
          imp_v1["affectedAddressCodes"] ==
          ["A01", "A02", "A03", "A04", "A05", "A10", "A11", "A12", "A13"],
          imp_v1["affectedAddressCodes"])

    _, imp_v3 = http("POST", "/api/network/impact", {"valveCodes": ["V3"]}, token=disp)
    check("单关 V3 影响集合精确为 A06-A09",
          imp_v3["affectedAddressCodes"] == ["A06", "A07", "A08", "A09"],
          imp_v3["affectedAddressCodes"])

    # ---------- 2. 受理与自动归属 ----------
    section("2. 六条模拟报修受理：原文保存 + 自动归属（含路口/同楼歧义）")
    rid = {}
    for item in reports_doc["initial"]:
        key = item["key"]
        st, detail = http("POST", "/api/reports", item["body"], token=disp, expect=200)
        rid[key] = detail["report"]["id"]
        cur = detail["currentJudgment"]
        check(f"{key} 原文已保存且编号生成",
              detail["report"]["code"].startswith("R-") and detail["report"]["rawAddress"])
        if "expectStatus" in item:
            check(f"{key} 自动归属状态={item['expectStatus']}",
                   cur["status"] == item["expectStatus"], cur)
        if "expectSegments" in item:
            check(f"{key} 唯一管段={item['expectSegments']}",
                  cur["selectedSegmentCodes"] == item["expectSegments"], cur)
        if "expectCandidateSegments" in item:
            check(f"{key} 候选管段={item['expectCandidateSegments']}（不自动合并事故）",
                  cur["candidateSegmentCodes"] == item["expectCandidateSegments"], cur)

    # 人工确认 R5/R6 到 S3，判断历史保留 AUTO 行
    for key in ("R5", "R6"):
        st, detail = http("POST", f"/api/reports/{rid[key]}/judgments",
                          {"segmentCodes": ["S3"], "status": "CONFIRMED",
                           "reason": "勘查与社区核对：积水来自滨河巷 S3 支管"},
                          token=disp, expect=200)
        versions = detail["judgments"]
        check(f"{key} 追加人工归属 v2=CONFIRMED/S3，且 v1 自动判断仍保留",
              len(versions) == 2 and versions[0]["source"] == "AUTO"
              and versions[1]["source"] == "MANUAL"
              and detail["currentJudgment"]["selectedSegmentCodes"] == ["S3"],
              [j["status"] for j in versions])

    # ---------- 3. 同段重拨：只添来源 ----------
    section("3. 同段重拨：R1/R2 确认 I2 后，R3 重拨自动添来源，不增事故/派工")
    _, i2 = http("POST", "/api/incidents",
                 {"title": "幸福路 S2 低压事故", "reportIds": [rid["R1"], rid["R2"]],
                  "boundarySegmentCodes": ["S2"],
                  "note": "同一条支管两端同时低压，确认同一事故"},
                 token=disp, expect=200)
    i2id = i2["id"]
    _, incidents_before = http("GET", "/api/incidents", token=disp)
    n_inc_before = len(incidents_before)

    redial_item = reports_doc["redial"]
    redial_body = dict(redial_item["body"])
    redial_body["redialOfReportId"] = rid[redial_item["redialOfKey"]]
    _, r3detail = http("POST", "/api/reports", redial_body, token=disp, expect=200)
    rid["R3"] = r3detail["report"]["id"]
    check("重拨单标记 redial=true 且自动挂到原事故 I2",
          r3detail["redial"] is True and r3detail["autoAttachedIncidentId"] == i2id,
          r3detail)

    _, incidents_after = http("GET", "/api/incidents", token=disp)
    check("重拨后事故数量不增加", len(incidents_after) == n_inc_before)
    _, i2detail = http("GET", f"/api/incidents/{i2id}", token=disp)
    check("I2 现有 3 条线索来源（R1/R2/R3）",
          len(i2detail["sources"]) == 3, len(i2detail["sources"]))
    check("来源中包含 REDIAL 类型",
          any(s["sourceType"] == "REDIAL" for s in i2detail["sources"]))
    check("I2 尚无任何派工单（重拨不触发派工）", len(i2detail["assignments"]) == 0)

    # 重复追加来源幂等
    st, _ = http("POST", f"/api/incidents/{i2id}/sources",
                 {"reportId": rid["R3"], "sourceType": "REDIAL"}, token=disp, expect=200)
    _, i2detail = http("GET", f"/api/incidents/{i2id}", token=disp)
    check("重复 sources 调用幂等，仍为 3 条来源", len(i2detail["sources"]) == 3)

    # ---------- 4. 相邻双支管：I3(S3) 与 I1(S1)，候选边界分开 ----------
    section("4. 相邻双支管：I3=S3、I1=S1 分别确认，候选关阀边界互不混淆")
    _, i3 = http("POST", "/api/incidents",
                 {"title": "滨河巷 S3 积水/低压事故",
                  "reportIds": [rid["R4"], rid["R5"], rid["R6"]],
                  "boundarySegmentCodes": ["S3"]},
                 token=disp, expect=200)
    i3id = i3["id"]
    _, i1 = http("POST", "/api/incidents",
                 {"title": "建设路 S1 低压事故（待压力复核）",
                  "reportIds": [rid["R7"]], "boundarySegmentCodes": ["S1"]},
                 token=disp, expect=200)
    i1id = i1["id"]
    require("两条相邻报修形成不同事故 I2≠I3，且还有 I1",
            len({i2id, i3id, i1id}) == 3)

    _, cand2 = http("GET", f"/api/incidents/{i2id}/candidate-isolations", token=disp)
    map2 = {"+".join(c["valveCodes"]): c["affectedAddressCodes"] for c in cand2}
    check("I2(S2) 候选包含 V2 且影响恰为 A01-A05",
          map2.get("V2") == ["A01", "A02", "A03", "A04", "A05"], map2)
    check("I2(S2) 候选包含 V1 且 V1 方案把 S1 的 A10-A13 也纳入",
          map2.get("V1") == ["A01", "A02", "A03", "A04", "A05", "A10", "A11", "A12", "A13"],
          map2)

    _, cand3 = http("GET", f"/api/incidents/{i3id}/candidate-isolations", token=disp)
    map3 = {"+".join(c["valveCodes"]): c["affectedAddressCodes"] for c in cand3}
    check("I3(S3) 候选为 V3 且影响恰为 A06-A09，与 I2 边界分开",
          map3.get("V3") == ["A06", "A07", "A08", "A09"], map3)
    check("V2 不是 S3 的候选隔离组合", "V2" not in map3, list(map3))

    _, cand1 = http("GET", f"/api/incidents/{i1id}/candidate-isolations", token=disp)
    check("I1(S1) 候选只有 V1",
          ["+".join(c["valveCodes"]) for c in cand1] == ["V1"], cand1)

    # 选方案 + 通知
    _, p2 = http("POST", f"/api/incidents/{i2id}/isolation-plans",
                 {"valveCodes": ["V2"]}, token=disp, expect=200)
    _, p3 = http("POST", f"/api/incidents/{i3id}/isolation-plans",
                 {"valveCodes": ["V3"]}, token=disp, expect=200)
    _, p1 = http("POST", f"/api/incidents/{i1id}/isolation-plans",
                 {"valveCodes": ["V1"]}, token=disp, expect=200)
    check("三个隔离方案均快照了影响地址",
          len(p2["affectedAddressCodes"]) == 5 and len(p3["affectedAddressCodes"]) == 4
          and len(p1["affectedAddressCodes"]) == 9)

    _, n2 = http("POST", f"/api/incidents/{i2id}/notify", {}, token=disp, expect=200)
    _, n3 = http("POST", f"/api/incidents/{i3id}/notify", {}, token=disp, expect=200)
    _, n1 = http("POST", f"/api/incidents/{i1id}/notify", {}, token=disp, expect=200)
    check("I2 通知 5 户 A01-A05",
          n2["sentCount"] == 5 and as_set(x["addressCode"] for x in n2["notifications"]) == set(s2))
    check("I3 通知 4 户 A06-A09", n3["sentCount"] == 4)
    check("I1(V1) 通知 9 户（含 S1）", n1["sentCount"] == 9)
    _, n2b = http("POST", f"/api/incidents/{i2id}/notify", {}, token=disp, expect=200)
    check("通知按方案幂等，重复 notify 不重发", n2b["alreadyNotified"] is True and n2b["sentCount"] == 5)

    # 不能用无效方案派维修
    st, bad_plan = http("POST", f"/api/incidents/{i3id}/isolation-plans",
                        {"valveCodes": ["V2"]}, token=disp)
    check("选择不能隔离 S3 的 V2 方案被 422 拒绝", st == 422 and bad_plan["code"] == "PLAN_DOES_NOT_ISOLATE")

    # ---------- 5. 资格门槛：许可 / 当班 / 车辆 / 工具 ----------
    section("5. 接单资格：许可、当班窗口、车辆、抢修工具")
    _, elig_nores = http("POST", f"/api/incidents/{i2id}/dispatch/eligibility",
                         {"kind": "REPAIR", "crewCode": "C_NORES",
                          "windowStart": "2026-11-26T05:00:00+08:00",
                          "windowEnd": "2026-11-26T07:00:00+08:00"}, token=disp)
    codes_nores = {c["code"]: c["passed"] for c in elig_nores["checks"]}
    check("C_NORES 资质/当班通过但车辆与工具不可用 → eligible=false",
          elig_nores["eligible"] is False
          and codes_nores.get("QUALIFICATION") is True
          and codes_nores.get("VEHICLE_AVAILABLE") is False
          and codes_nores.get("TOOLS_AVAILABLE") is False, elig_nores)

    _, elig_shift = http("POST", f"/api/incidents/{i2id}/dispatch/eligibility",
                         {"kind": "REPAIR", "crewCode": "C_SOLE",
                          "windowStart": "2026-11-26T20:00:00+08:00",
                          "windowEnd": "2026-11-26T22:00:00+08:00"}, token=disp)
    shift_check = next(c for c in elig_shift["checks"] if c["code"] == "WINDOW_WITHIN_SHIFT")
    check("C_SOLE 申请当班外窗口(20-22)被当班校验拒绝",
          elig_shift["eligible"] is False and shift_check["passed"] is False)

    st, scout_repair = http("POST", f"/api/incidents/{i2id}/dispatch/repair",
                            {"crewCode": "C_SCOUT",
                             "windowStart": "2026-11-26T05:00:00+08:00",
                             "windowEnd": "2026-11-26T07:00:00+08:00"}, token=disp)
    check("只有勘查许可的 C_SCOUT 不能接维修单（422 资质失败）",
          st == 422 and scout_repair["code"] == "DISPATCH_ELIGIBILITY_FAILED",
          (st, scout_repair))

    # ---------- 6. 唯一持证班组并发双派单 ----------
    section("6. 并发互斥：同一 C_SOLE 同一窗口被 I2/I3 同时派单，恰好一成一败")

    def race_dispatch(incident_id):
        barrier.wait()
        return http("POST", f"/api/incidents/{incident_id}/dispatch/repair",
                    {"crewCode": "C_SOLE",
                     "strategy": "DIRECT",
                     "windowStart": "2026-11-26T04:00:00+08:00",
                     "windowEnd": "2026-11-26T04:40:00+08:00"}, token=disp)

    barrier = threading.Barrier(2)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futs = [pool.submit(race_dispatch, i2id), pool.submit(race_dispatch, i3id)]
        race_results = [f.result() for f in futs]
    statuses = sorted(s for s, _ in race_results)
    check("并发两个派单：状态码恰为一个 200、一个 409", statuses == [200, 409], statuses)
    conflict = next(b for s, b in race_results if s == 409)
    winner = next(b for s, b in race_results if s == 200)
    check("失败方错误码 CREW_ALREADY_BOOKED 且细节指向占用方",
          conflict["code"] == "CREW_ALREADY_BOOKED"
          and conflict["details"]["resourceCode"] == "C_SOLE"
          and conflict["details"]["heldByAssignmentCode"], conflict)

    winner_id = winner["id"]
    _, occ_w = http("GET", f"/api/assignments/{winner_id}/occupancies", token=disp)
    check("成功方持有 CREW/VEHICLE/TOOL/PERMIT 共 5 项 HELD 占用",
          len([o for o in occ_w if o["status"] == "HELD"]) == 5,
          [(o["resourceType"], o["resourceCode"]) for o in occ_w])

    _, cancelled = http("POST", f"/api/assignments/{winner_id}/cancel",
                        {"reason": "并发回放后的试探派单，调度收回"}, token=disp, expect=200)
    check("取消成功派工单", cancelled["status"] == "CANCELLED")
    _, occ_w2 = http("GET", f"/api/assignments/{winner_id}/occupancies", token=disp)
    check("取消后其全部占用 RELEASED 且记录释放原因（可追溯）",
          all(o["status"] == "RELEASED" and o["releaseReason"] for o in occ_w2))

    # ---------- 7. 路线 A：I3 先派勘查 ----------
    section("7. 路线A（勘查先行）：I3 勘查班 04:25 到场，顺序/角色约束生效")
    _, scout_asg = http("POST", f"/api/incidents/{i3id}/dispatch/scout",
                        {"crewCode": "C_SCOUT",
                         "windowStart": "2026-11-26T04:20:00+08:00",
                         "windowEnd": "2026-11-26T05:00:00+08:00"},
                        token=disp, expect=200)
    scout_id = scout_asg["id"]

    def receipt(incident_id, tpl_key, assignment_id, token=None, extra=None):
        body = dict(receipt_tpls[tpl_key])
        body["assignmentId"] = assignment_id
        if extra:
            body.update(extra)
        return http("POST", f"/api/incidents/{incident_id}/receipts", body,
                    token=token or disp)

    st, arr = receipt(i3id, "I3_SCOUT_ARRIVAL", scout_id)
    check("勘查单提交 ARRIVAL 成功，归属 C_SCOUT",
          st == 200 and arr["type"] == "ARRIVAL" and arr["crewCode"] == "C_SCOUT", (st, arr))

    st, order_err = http("POST", f"/api/incidents/{i3id}/receipts",
                         {"assignmentId": scout_id, "type": "REPAIR",
                          "eventAt": "2026-11-26T04:30:00+08:00"}, token=disp)
    check("跳过 ISOLATION 直接 REPAIR 被 409 RECEIPT_ORDER_CONFLICT 拒绝",
          st == 409 and order_err["code"] == "RECEIPT_ORDER_CONFLICT"
          and order_err["details"]["expected"] == "ISOLATION", (st, order_err))

    st, role_err = http("POST", f"/api/incidents/{i3id}/receipts",
                        {"assignmentId": scout_id, "type": "ISOLATION",
                         "eventAt": "2026-11-26T04:32:00+08:00"}, token=disp)
    check("勘查单不能提交 ISOLATION（必须 REPAIR 派工单+阀许可）",
          st == 422 and role_err["code"] == "REPAIR_ASSIGNMENT_REQUIRED", (st, role_err))

    _, done_scout = http("POST", f"/api/assignments/{scout_id}/complete",
                         {"reason": "已确认 V3 阀门故障并交接维修队"}, token=disp, expect=200)
    check("勘查派工单完成交接，勘查资源占用释放", done_scout["status"] == "COMPLETED")

    # ---------- 8. 路线 B：I2 捆绑暂留维修队+V2 阀票窗口 ----------
    section("8. 路线B（捆绑暂留）：I2 首次出动前暂留 C_SOLE + POP-V2 + 车/工具")
    _, a21 = http("POST", f"/api/incidents/{i2id}/dispatch/repair",
                  {"crewCode": "C_SOLE", "strategy": "RESERVE_BUNDLE", "holdOnly": True,
                   "windowStart": "2026-11-26T05:00:00+08:00",
                   "windowEnd": "2026-11-26T07:00:00+08:00",
                   "note": "根因未最终确认也先捆绑暂留，保证维修接续"},
                  token=disp, expect=200)
    a21id = a21["id"]
    check("暂留派工单状态 PROVISIONAL", a21["status"] == "PROVISIONAL")
    _, occ21 = http("GET", f"/api/assignments/{a21id}/occupancies", token=disp)
    held21 = {(o["resourceType"], o["resourceCode"]) for o in occ21 if o["status"] == "HELD"}
    check("暂留即锁定 C_SOLE、POP-V2、VEH-1 与两件工具",
          ("CREW", "C_SOLE") in held21 and ("VALVE_PERMIT", "POP-V2") in held21
          and ("VEHICLE", "VEH-1") in held21 and ("TOOL", "TOOL-CLAMP") in held21
          and ("TOOL", "TOOL-WELDER") in held21, held21)
    _, issued = http("POST", f"/api/assignments/{a21id}/issue", {}, token=disp, expect=200)
    check("暂留单下达后 DISPATCHED", issued["status"] == "DISPATCHED")

    # 路线 A 的 I3 此时尝试锁同一持证队的重叠窗口 → 被 I2 先占
    st, lock_fail = http("POST", f"/api/incidents/{i3id}/dispatch/repair",
                         {"crewCode": "C_SOLE", "strategy": "LOCK_AFTER_SCOUT",
                          "windowStart": "2026-11-26T06:00:00+08:00",
                          "windowEnd": "2026-11-26T08:00:00+08:00"}, token=disp)
    check("勘查先行的 I3 锁定 06-08 维修窗口时，被路线B暂留的 I2 占队 → 409",
          st == 409 and lock_fail["code"] == "CREW_ALREADY_BOOKED", (st, lock_fail))

    # ---------- 9. I2 第一周期回执 + 复压失败 ----------
    section("9. I2 第一周期五类回执；复压值低于 S2 区间，禁关单")
    for tpl in ["I2_C1_ARRIVAL", "I2_C1_ISOLATION", "I2_C1_REPAIR", "I2_C1_FLUSH"]:
        st, rc = receipt(i2id, tpl, a21id)
        check(f"{tpl} 提交成功并归属 A2-1/C_SOLE",
              st == 200 and rc["assignmentId"] == a21id and rc["crewCode"] == "C_SOLE", (st, rc))

    st, fail_rc = receipt(i2id, "I2_C1_REPRESSURE_FAIL", a21id)
    check("复压失败回执被保存且 verificationStatus=FAILED（历史不抹掉）",
          st == 200 and fail_rc["verificationStatus"] == "FAILED"
          and any("超出" in r for r in fail_rc["verificationReasons"]), fail_rc)

    st, close_fail = http("POST", f"/api/incidents/{i2id}/close", {}, token=disp)
    check("复压未通过时关单被 409 REPRESSURE_NOT_PASSED 拒绝",
          st == 409 and close_fail["code"] == "REPRESSURE_NOT_PASSED", (st, close_fail))

    # 失败后不能在旧周期直接补回执
    st, chain_full = http("POST", f"/api/incidents/{i2id}/receipts",
                          {"assignmentId": a21id, "type": "ARRIVAL",
                           "eventAt": "2026-11-26T07:05:00+08:00"}, token=disp)
    check("未重新派工前不能开启/插入新回执（链停在 REPRESSURE 失败态）",
          st == 409 and chain_full["code"] in ("RECEIPT_CHAIN_COMPLETE", "RECEIPT_ORDER_CONFLICT"),
          (st, chain_full))

    # ---------- 10. 重新派工 + 第二周期通过 + 占用释放 ----------
    section("10. I2 复压失败后重新派工：旧占用释放、新周期五回执通过、关单释放全部占用")
    _, a22 = http("POST", f"/api/incidents/{i2id}/redispatch",
                  {"crewCode": "C_SOLE",
                   "windowStart": "2026-11-26T07:20:00+08:00",
                   "windowEnd": "2026-11-26T09:20:00+08:00",
                   "note": "返工并重新派工"}, token=disp, expect=200)
    a22id = a22["id"]
    check("新派工单 attemptNo=2/cycleNo=2，parent 指向 A2-1",
          a22["attemptNo"] == 2 and a22["cycleNo"] == 2
          and a22["parentAssignmentId"] == a21id, a22)

    _, occ21_after = http("GET", f"/api/assignments/{a21id}/occupancies", token=disp)
    check("重新派工后 A2-1 原占用全部 RELEASED，原因含 REDISPATCH",
          all(o["status"] == "RELEASED" and "REDISPATCH" in (o["releaseReason"] or "")
              for o in occ21_after) and len(occ21_after) == 5, occ21_after)

    st, old_cycle_rc = http("POST", f"/api/incidents/{i2id}/receipts",
                            {"assignmentId": a21id, "type": "ARRIVAL",
                             "eventAt": "2026-11-26T07:21:00+08:00"}, token=disp)
    check("旧派工单不能在新周期提交回执（RECEIPT_WRONG_CYCLE，历史周期封存）",
          st == 409 and old_cycle_rc["code"] == "RECEIPT_WRONG_CYCLE", (st, old_cycle_rc))

    for tpl in ["I2_C2_ARRIVAL", "I2_C2_ISOLATION", "I2_C2_REPAIR",
                "I2_C2_FLUSH", "I2_C2_REPRESSURE_OK"]:
        st, rc = receipt(i2id, tpl, a22id)
        check(f"{tpl} 新周期提交成功并归属 A2-2",
              st == 200 and rc["cycleNo"] == 2 and rc["assignmentId"] == a22id, (st, rc))
    _, i2detail2 = http("GET", f"/api/incidents/{i2id}", token=disp)
    rep_ok = [r for r in i2detail2["receipts"] if r["type"] == "REPRESSURE" and r["cycleNo"] == 2][0]
    check("第二周期复压 PASSED，校时差值在容差内",
          rep_ok["verificationStatus"] == "PASSED"
          and rep_ok["correctedTimeDeltaSeconds"] <= rep_ok["timeToleranceSeconds"], rep_ok)

    _, closed2 = http("POST", f"/api/incidents/{i2id}/close", {}, token=disp, expect=200)
    check("I2 关单成功 CLOSED", closed2["status"] == "CLOSED")
    _, occ22 = http("GET", f"/api/assignments/{a22id}/occupancies", token=disp)
    check("关单后 A2-2 占用全部 RELEASED/INCIDENT_CLOSED",
          all(o["status"] == "RELEASED" and o["releaseReason"] == "INCIDENT_CLOSED" for o in occ22))

    # ---------- 11. I3 锁定晚窗口并闭环，核对每条回执归属 ----------
    section("11. I3 避开 I2 窗口锁定 09:30-11:30，闭环；核对每条历史回执归属")
    st, a32 = http("POST", f"/api/incidents/{i3id}/dispatch/repair",
                   {"crewCode": "C_SOLE", "strategy": "LOCK_AFTER_SCOUT",
                    "windowStart": "2026-11-26T09:30:00+08:00",
                    "windowEnd": "2026-11-26T11:30:00+08:00"}, token=disp, expect=200)
    a32id = a32["id"]
    check("I3 在 I2 窗口结束(09:20)后锁定同一持证队成功（半开区间相邻不冲突）",
          a32["status"] == "DISPATCHED", (st, a32))

    for tpl in ["I3_ISOLATION", "I3_REPAIR", "I3_FLUSH", "I3_REPRESSURE_OK"]:
        st, rc = receipt(i3id, tpl, a32id)
        check(f"{tpl} 提交成功", st == 200 and rc["crewCode"] == "C_SOLE", (st, rc))

    _, closed3 = http("POST", f"/api/incidents/{i3id}/close", {}, token=disp, expect=200)
    check("I3 复压通过并关单", closed3["status"] == "CLOSED")

    _, d3 = http("GET", f"/api/incidents/{i3id}", token=disp)
    by_type = {r["type"]: r for r in d3["receipts"]}
    check("I3 共 5 条回执且类型顺序正确",
          [r["type"] for r in d3["receipts"]] ==
          ["ARRIVAL", "ISOLATION", "REPAIR", "FLUSH", "REPRESSURE"],
          [r["type"] for r in d3["receipts"]])
    check("到场回执归属勘查班 C_SCOLE/勘查单，其余四条归属 C_SOLE/维修单",
          by_type["ARRIVAL"]["crewCode"] == "C_SCOUT"
          and by_type["ARRIVAL"]["assignmentId"] == scout_id
          and all(by_type[t]["crewCode"] == "C_SOLE" and by_type[t]["assignmentId"] == a32id
                  for t in ["ISOLATION", "REPAIR", "FLUSH", "REPRESSURE"]),
          {t: (by_type[t]["crewCode"], by_type[t]["assignmentCode"]) for t in by_type})

    # CREW 账号也可提交回执的角色验证（viewer 不行）——用一个轻量 403 检查
    st, _ = http("POST", f"/api/incidents/{i3id}/receipts",
                 {"assignmentId": a32id, "type": "X", "eventAt": "2026-11-26T11:20:00+08:00"},
                 token=viewer_token)
    check("VIEWER 不能提交现场回执（403）", st == 403, f"status={st}")

    # ---------- 12. 压力复核推翻：范围/占用/通知更正 ----------
    section("12. I1 压力复核推翻 S1/V1 → S2/V2：范围 diff、阀票换占、旧通知更正链")
    _, a1 = http("POST", f"/api/incidents/{i1id}/dispatch/repair",
                 {"crewCode": "C_SOLE", "strategy": "RESERVE_BUNDLE",
                  "windowStart": "2026-11-26T13:00:00+08:00",
                  "windowEnd": "2026-11-26T15:00:00+08:00"}, token=disp, expect=200)
    a1id = a1["id"]

    _, review_result = http("POST", f"/api/incidents/{i1id}/pressure-reviews", {
        "verdict": "OVERTURNS",
        "reviewer": "压力复核岗",
        "evidenceReadingCodes": ["PR-REV-S1", "PR-REV-S2"],
        "correctedBoundarySegmentCodes": ["S2"],
        "correctedValveCodes": ["V2"],
        "note": "S1 压力全程正常，异常曲线与校时后的读数均指向 S2；原 S1/V1 判断推翻。"
    }, token=disp, expect=200)
    corr = review_result["correction"]
    require("生成更正记录 correction", corr is not None)
    check("更正记录保留旧边界 S1/旧阀 V1/旧范围 9 户",
          corr["oldBoundarySegments"] == ["S1"] and corr["oldValveCodes"] == ["V1"]
          and corr["oldAddressCodes"] ==
          ["A01", "A02", "A03", "A04", "A05", "A10", "A11", "A12", "A13"], corr)
    check("新边界 S2/新阀 V2/新范围 A01-A05",
          corr["newBoundarySegments"] == ["S2"] and corr["newValveCodes"] == ["V2"]
          and corr["newAddressCodes"] == ["A01", "A02", "A03", "A04", "A05"], corr)
    check("地址 diff：移出 A10-A13，无新增",
          corr["removedAddressCodes"] == ["A10", "A11", "A12", "A13"]
          and corr["addedAddressCodes"] == [], corr)
    check("阀门许可占用更正：释放 POP-V1，新占 POP-V2",
          corr["releasedPermitCodes"] == ["POP-V1"]
          and corr["reservedPermitCodes"] == ["POP-V2"], corr)

    _, occ1 = http("GET", f"/api/assignments/{a1id}/occupancies", token=disp)
    popv1 = [o for o in occ1 if o["resourceCode"] == "POP-V1"]
    popv2 = [o for o in occ1 if o["resourceCode"] == "POP-V2"]
    check("I1 占用明细：POP-V1 已 RELEASED(PRESSURE_REVIEW_CORRECTION)，POP-V2 HELD",
          len(popv1) == 1 and popv1[0]["status"] == "RELEASED"
          and "PRESSURE_REVIEW_CORRECTION" in popv1[0]["releaseReason"]
          and len(popv2) == 1 and popv2[0]["status"] == "HELD", occ1)

    _, d1 = http("GET", f"/api/incidents/{i1id}", token=disp)
    initial_notes = [n for n in d1["notifications"] if n["kind"] == "INITIAL"]
    correction_notes = [n for n in d1["notifications"] if n["kind"] == "CORRECTION"]
    cancel_notes = [n for n in d1["notifications"] if n["kind"] == "CORRECTION_CANCEL"]
    check("9 条原始通知全部置 SUPERSEDED 并挂更正 id（不删除、不伪装未发生）",
          len(initial_notes) == 9
          and all(n["status"] == "SUPERSEDED" and n["supersededByCorrectionId"] == corr["id"]
                  for n in initial_notes))
    check("向新范围 5 户发 CORRECTION，向移出的 A10-A13 发 4 条 CORRECTION_CANCEL",
          len(correction_notes) == 5 and len(cancel_notes) == 4
          and as_set(n["addressCode"] for n in cancel_notes) ==
          {"A10", "A11", "A12", "A13"})
    a12_cancel = next(n for n in cancel_notes if n["addressCode"] == "A12")
    old_a12 = next(n for n in initial_notes if n["addressCode"] == "A12")
    check("A12 取消通知通过 replacesNotificationId 链接到被作废的旧通知",
          a12_cancel["replacesNotificationId"] == old_a12["id"]
          and old_a12["status"] == "SUPERSEDED")
    check("I1 边界已更新为 S2，方案版本变为 2 且旧方案 CORRECTED",
          d1["incident"]["boundarySegmentCodes"] == ["S2"]
          and d1["incident"]["revision"] == 2
          and [p["status"] for p in d1["plans"]].count("CORRECTED") == 1
          and d1["plans"][-1]["status"] == "ACTIVE")

    # 按更正后的 S2 完成 I1 并关单
    for tpl in ["I1_ARRIVAL", "I1_ISOLATION", "I1_REPAIR", "I1_FLUSH", "I1_REPRESSURE_OK"]:
        st, rc = receipt(i1id, tpl, a1id)
        check(f"更正后 {tpl} 提交并按 S2 核验", st == 200, (st, rc))
    _, closed1 = http("POST", f"/api/incidents/{i1id}/close", {}, token=disp, expect=200)
    check("更正后的 I1 复压通过并关单", closed1["status"] == "CLOSED")

    # ---------- 13. 校时容差/区间试算 ----------
    section("13. 复压核验试算：正常区间与采样校时容差分别可判")
    _, vbad_val = http("POST", "/api/pressure/verify-repressure",
                       {"readingCode": "PR-S2-FAIL",
                        "eventAt": "2026-11-26T06:50:00+08:00",
                        "boundarySegmentCodes": ["S2"]}, token=disp)
    check("低压读数判 FAILED 且原因是超出区间（校时后时刻其实接近）",
          vbad_val["verificationStatus"] == "FAILED"
          and any("正常区间" in r for r in vbad_val["reasons"])
          and not any("容差" in r for r in vbad_val["reasons"]), vbad_val)

    _, vbad_time = http("POST", "/api/pressure/verify-repressure",
                        {"readingCode": "PR-TIME-BAD",
                         "eventAt": "2026-11-26T06:50:00+08:00",
                         "boundarySegmentCodes": ["S2"]}, token=disp)
    check("压力值合格但采样时刻超校时容差 → FAILED",
          vbad_time["verificationStatus"] == "FAILED"
          and any("容差" in r for r in vbad_time["reasons"]), vbad_time)

    _, vok = http("POST", "/api/pressure/verify-repressure",
                  {"readingCode": "PR-S2-OK",
                   "eventAt": "2026-11-26T09:05:00+08:00",
                   "boundarySegmentCodes": ["S2"]}, token=disp)
    check("PR-S2-OK 区间与校时容差均通过 → PASSED",
          vok["verificationStatus"] == "PASSED", vok)


    # ---------- 15. 材料/仓库：需求 → 配料 → 扫码 → 召回 → 验收 ----------
    section("15. 维修方案材料需求：口径/材质/连接/额定压力传给仓库；缺料不得同系列顶替")
    wh_token = login("warehouse", "Ware@12345")
    check("仓库材料员账号可登录", bool(wh_token))

    st, batches = http("GET", "/api/materials/batches", token=wh_token, expect=200)
    batch_by_no = {b["batchNo"]: b for b in batches}
    check("仓库有足量 DN100/PN16 管材与 DN80/PN10 管材两种规格",
          batch_by_no["PIPE-DN100-PN16-Q1"]["issuableQty"] >= 2
          and batch_by_no["PIPE-DN80-PN10-Q1"]["issuableQty"] >= 2
          and batch_by_no["SEAL-DN100-PN16-Q1"]["issuableQty"] >= 2, batches)
    check("DN100/PN10 外形相似件存在但不满足 PN16（paramKey 可核对等价性）",
          batch_by_no["PIPE-DN100-PN10-X"]["qualified"] is True
          and batch_by_no["PIPE-DN100-PN10-X"]["paramKey"] == "DN100/DUCTILE_IRON/FLANGE/PN10")

    # 缺料探针：需求 PN16 但仓库只剩 PN10 时，不得自动顶替
    _, probe_report = http("POST", "/api/reports", {
        "hotlineCallId": "HC-20261127-1999", "callerPhone": "13900001999",
        "rawAddress": "幸福路1号", "description": "缺料探针报修",
        "receivedAt": "2026-11-27T07:50:00+08:00"}, token=disp, expect=200)
    _, probe_inc = http("POST", "/api/incidents",
                        {"title": "缺料探针（PN16 需求 vs 仅 PN10 库存）",
                         "reportIds": [probe_report["report"]["id"]],
                         "boundarySegmentCodes": ["S2"]}, token=disp, expect=200)
    st, probe = http("POST", f"/api/incidents/{probe_inc['id']}/repair-plans",
                     {"lines": orders["shortageProbe"]["lines"], "granularity": "BATCH",
                      "approve": True, "note": "缺料探针"}, token=disp)
    check("缺料时返回 409 MATERIAL_SHORTAGE 而非用 PN10 顶 PN16",
          st == 409 and probe["code"] == "MATERIAL_SHORTAGE", (st, probe))
    nm = probe["details"]["nearMatches"]
    check("缺料详情列出近似件 PIPE-DN100-PN10-X 且标明压力等级不符",
          any(m["batchNo"] == orders["shortageProbe"]["expectNearMatchBatch"]
              and any("PN" in x for x in m["mismatches"]) for m in nm), nm)

    # ---------- 16. 两张维修单：方案变更（现场 DN100→DN80/PN10） ----------
    section("16. 维修单 M1：BATCH 粒度获批锁批次；现场改口径后释放旧件并重做配料")
    m1 = orders["pipePlanChange"]
    _, r_m1 = http("POST", "/api/reports", m1["report"], token=disp, expect=200)
    rid_m1 = r_m1["report"]["id"]
    _, inc_m1 = http("POST", "/api/incidents",
                     {"title": m1["title"], "reportIds": [rid_m1],
                      "boundarySegmentCodes": m1["boundary"]}, token=disp, expect=200)
    m1id = inc_m1["id"]
    http("POST", f"/api/incidents/{m1id}/isolation-plans",
         {"valveCodes": m1["valveCodes"]}, token=disp, expect=200)

    st, plan1 = http("POST", f"/api/incidents/{m1id}/repair-plans",
                     {"lines": m1["v1Lines"], "granularity": "BATCH", "approve": True,
                      "preferredBatchNo": m1["preferredSealBatch"],
                      "note": "原估算 DN100/PN16，仓库按此配料"}, token=disp)
    _, pv1_before = http("GET", f"/api/incidents/{m1id}/material-provision", token=wh_token, expect=200)
    check("方案 v1 获批即锁定具体合格批次（BATCH 粒度）",
          st == 200 and plan1["provision"]["status"] == "LOCKED"
          and plan1["provision"]["granularity"] == "BATCH"
          and pv1_before["reveal"]["hasConcreteBatch"] is True, (st, plan1))
    _, pv1 = http("GET", f"/api/incidents/{m1id}/material-provision", token=wh_token, expect=200)
    check("v1 配料单已给出具体锁定的 DN100 批次与 DN100 密封件批次",
          pv1["reveal"]["hasConcreteBatch"] is True
          and "PIPE-DN100-PN16-Q1" in pv1["reveal"]["lockedBatches"]
          and "SEAL-DN100-PN16-Q1" in pv1["reveal"]["lockedBatches"], pv1["reveal"])
    check("v1 配料时 DN100 密封件批次被加锁（quantityLocked ≥ 2）",
          next(b for b in http("GET", "/api/materials/batches", token=wh_token)[1]
               if b["batchNo"] == m1["preferredSealBatch"])["quantityLocked"] >= 2)

    # ---------- 16b. 同场景 CATEGORY 粒度对照：方案变更只需改参数类别，装车时再挑批次 ----------
    section("16b. 对照 CATEGORY 粒度：方案变更后旧件自释放，装车前才落具体批次")
    _, r_cat = http("POST", "/api/reports", {
        "hotlineCallId": "HC-20261127-1998", "callerPhone": "13900001998",
        "rawAddress": "幸福路4号", "description": "S2 对照单：CATEGORY 粒度方案变更",
        "receivedAt": "2026-11-27T07:55:00+08:00"}, token=disp, expect=200)
    _, inc_cat = http("POST", "/api/incidents",
                      {"title": "S2 CATEGORY 粒度方案变更对照单",
                       "reportIds": [r_cat["report"]["id"]], "boundarySegmentCodes": ["S2"]},
                      token=disp, expect=200)
    catid = inc_cat["id"]
    http("POST", f"/api/incidents/{catid}/isolation-plans", {"valveCodes": ["V2"]}, token=disp, expect=200)
    _, cat_v1 = http("POST", f"/api/incidents/{catid}/repair-plans",
                     {"lines": m1["v1Lines"], "granularity": "CATEGORY", "approve": True,
                      "note": "CATEGORY v1：原估 DN100/PN16"}, token=disp, expect=200)
    _, pv_cat_v1 = http("GET", f"/api/incidents/{catid}/material-provision", token=wh_token, expect=200)
    check("CATEGORY v1 获批时只锁参数类别，不出现具体批次",
          cat_v1["provision"]["granularity"] == "CATEGORY"
          and not pv_cat_v1["reveal"]["hasConcreteBatch"], pv_cat_v1["reveal"])
    _, cat_v2 = http("POST", f"/api/incidents/{catid}/repair-plans",
                     {"lines": m1["v2Lines"], "granularity": "CATEGORY", "approve": True,
                      "note": "CATEGORY v2：现场确认 DN80/PN10"}, token=disp, expect=200)
    _, cat_hist = http("GET", f"/api/incidents/{catid}/material-provisions", token=wh_token, expect=200)
    check("CATEGORY 方案变更时旧配料单被释放（未占用任何具体批次，无需退回扫描）",
          cat_v2["plan"]["versionNo"] == 2
          and cat_hist[0]["provision"]["status"] in ("RELEASED", "REPLACED")
          and all(a["status"] != "ACTIVE" for a in cat_hist[0]["allocations"]),
          cat_hist[0]["provision"])
    st, cat_disp2 = http("POST", f"/api/incidents/{catid}/dispatch/repair",
                         {"crewCode": "C_SOLE", "strategy": "DIRECT",
                          "windowStart": "2026-11-27T06:00:00+08:00",
                          "windowEnd": "2026-11-27T07:00:00+08:00"}, token=disp)
    _, pv_cat2 = http("GET", f"/api/incidents/{catid}/material-provision", token=wh_token, expect=200)
    check("CATEGORY 装车前按当时合格库存挑定具体批次（此时才出现批次号）",
          st == 200 and pv_cat2["reveal"]["hasConcreteBatch"] is True
          and len(pv_cat2["reveal"]["lockedBatches"]) >= 2, (st, pv_cat2["reveal"]))
    http("POST", f"/api/assignments/{cat_disp2['id']}/cancel",
         {"reason": "CATEGORY 方案变更对照完成，收回派工"}, token=disp, expect=200)

    st, plan2 = http("POST", f"/api/incidents/{m1id}/repair-plans",
                     {"lines": m1["v2Lines"], "granularity": "BATCH", "approve": True,
                      "note": "现场确认一处为 DN80/PN10，改口径重做配料"}, token=disp)
    _, pv2_before = http("GET", f"/api/incidents/{m1id}/material-provision", token=wh_token, expect=200)
    check("方案变更 v2 获批，新配料单锁定 DN100×1 + DN80/PN10×2",
          st == 200 and plan2["plan"]["versionNo"] == 2
          and "PIPE-DN80-PN10-Q1" in [a["batchNo"] for a in pv2_before["allocations"]]
          and "PIPE-DN100-PN16-Q1" in [a["batchNo"] for a in pv2_before["allocations"]],
          pv2_before["allocations"])
    _, pvs1 = http("GET", f"/api/incidents/{m1id}/material-provisions", token=wh_token, expect=200)
    old1 = next(pv for pv in pvs1 if pv["provision"]["planVersionNo"] == 1)
    check("旧配料单 v1 已 RELEASED/REPLACED（旧件锁定被释放，历史不删除）",
          old1["provision"]["status"] in ("REPLACED", "RELEASED")
          and old1["provision"]["releaseReason"], old1["provision"])
    check("方案变更后旧方案的 DN100 密封件批次不再被该工地锁定",
          pv2_before["provision"]["granularity"] == "BATCH"
          and "SEAL-DN100-PN16-Q1" not in [a["batchNo"] for a in pv2_before["allocations"]
                                            if a["status"] == "ACTIVE"])

    # 派工发车：BATCH 粒度物料确定
    _, a_m1 = http("POST", f"/api/incidents/{m1id}/dispatch/repair",
                   {"crewCode": "C_SOLE", "strategy": "DIRECT",
                    "windowStart": m1["windowStart"], "windowEnd": m1["windowEnd"],
                    "note": "按 S2 方案 v2 发车"}, token=disp, expect=200)
    check("维修单 M1 派工发车时物料已确定（BATCH 粒度直接可发）",
          a_m1["status"] == "DISPATCHED", a_m1)
    _, pv_m1 = http("GET", f"/api/incidents/{m1id}/material-provision", token=wh_token, expect=200)
    check("发车即绑定配料单与派工单，批次仍为具体 DN100×1 + DN80/PN10×2",
          pv_m1["provision"]["assignmentId"] == a_m1["id"]
          and set(pv_m1["reveal"]["lockedBatches"]) >= {"PIPE-DN100-PN16-Q1", "PIPE-DN80-PN10-Q1"},
          pv_m1["reveal"])

    # 现场扫码领用 + 安装（记录安装位置）
    issue_items = [{"batchNo": it["batchNo"], "qty": it["qty"], "lineNo": it["lineNo"], "segmentCode": "S2"}
                   for it in m1["installs"]]
    st, iss = http("POST", f"/api/incidents/{m1id}/material-scans/issue",
                   {"items": issue_items, "eventAt": "2026-11-27T08:45:00+08:00"}, token=wh_token)
    check("现场扫码领用 DN100 管、DN80 管，数量与锁定批次匹配",
          st == 200 and iss["reveal"]["issuedQty"] == 3, (st, iss))
    for it in m1["installs"]:
        st, inst_scan = http("POST", f"/api/incidents/{m1id}/material-scans/install",
                             {"item": {"batchNo": it["batchNo"], "qty": it["qty"], "lineNo": it["lineNo"],
                                       "segmentCode": "S2", "locationText": "S2 幸福路3号"}} ,
                             token=crew_token)
        check(f"扫码安装 {it['batchNo']} × {it['qty']} 并记录安装位置 S2",
              st == 200 and inst_scan["kind"] == "INSTALL" and inst_scan["segmentCode"] == "S2",
              (st, inst_scan))
    # 工单 M1 回执闭环（材料已安装，回执按顺序提交）
    for tpl_type, t in m1["receipts"].items():
        body = {"assignmentId": a_m1["id"], "type": tpl_type, "eventAt": t}
        if tpl_type == "REPRESSURE":
            body["readingCode"] = m1["reading"]
        st, rc = http("POST", f"/api/incidents/{m1id}/receipts", body, token=disp)
        check(f"M1 {tpl_type} 回执成功", st == 200, (st, rc))

    # ---------- 17. 维修单 M2：方案先锁定 DN100 密封件批次，领料后召回 ----------
    section("17. 维修单 M2：DN100 密封件配料后被召回（已领料后召回顺序）")
    m2 = orders["sealRecall"]
    _, r_m2 = http("POST", "/api/reports", m2["report"], token=disp, expect=200)
    rid_m2 = r_m2["report"]["id"]
    _, inc_m2 = http("POST", "/api/incidents",
                     {"title": m2["title"], "reportIds": [rid_m2],
                      "boundarySegmentCodes": m2["boundary"]}, token=disp, expect=200)
    m2id = inc_m2["id"]
    http("POST", f"/api/incidents/{m2id}/isolation-plans",
         {"valveCodes": m2["valveCodes"]}, token=disp, expect=200)
    st, plan_m2 = http("POST", f"/api/incidents/{m2id}/repair-plans",
                       {"lines": m2["lines"], "granularity": "BATCH", "approve": True,
                        "preferredBatchNo": m2["plannedBatch"],
                        "note": "M2 锁定额定批次 SEAL-DN100-PN16-Q1"}, token=disp)
    _, pv_m2_before = http("GET", f"/api/incidents/{m2id}/material-provision", token=wh_token, expect=200)
    check("M2 方案获批锁定 SEAL-DN100-PN16-Q1（具体批次）",
          st == 200 and m2["plannedBatch"] in
          [a["batchNo"] for a in pv_m2_before["allocations"] if a["status"] == "ACTIVE"],
          pv_m2_before["allocations"])

    # 发车 + 已领料（现场还没装）
    _, a_m2 = http("POST", f"/api/incidents/{m2id}/dispatch/repair",
                   {"crewCode": "C_SOLE", "strategy": "RESERVE_BUNDLE", "holdOnly": True,
                    "windowStart": m2["windowStart"], "windowEnd": m2["windowEnd"]}, token=disp, expect=200)
    st, iss_m2 = http("POST", f"/api/incidents/{m2id}/material-scans/issue",
                      {"items": [{"batchNo": m2["plannedBatch"], "qty": 2, "lineNo": 1, "segmentCode": "S3"}],
                       "eventAt": "2026-11-27T10:45:00+08:00"}, token=wh_token)
    check("M2 班组领用 SEAL-DN100-PN16-Q1 ×2（已领未装）",
          st == 200 and iss_m2["reveal"]["issuedQty"] == 2, (st, iss_m2))

    _, risk_before = http("GET", f"/api/materials/batches/{m2['plannedBatch']}/risk", token=wh_token, expect=200)
    check("召回前风险清单显示该批次已领未装 2 件在 M2 工单",
          any(x["incidentId"] == m2id and x["qty"] == 2
              for x in risk_before["issuedNotInstalled"]), risk_before["issuedNotInstalled"])

    st, recall_res = http("POST", "/api/materials/recalls",
                          {"batchNo": m2["plannedBatch"], "kind": "QUALITY_RECALL",
                           "reason": orders["recall"]["reason"],
                           "publishedAt": orders["recall"]["publishedAt"]}, token=wh_token)
    check("供应商发布召回成功，批次状态 RECALLED",
          st == 200 and recall_res["batchStatus"] == "RECALLED", (st, recall_res))
    check("召回后该批次可用发数量 issuableQty=0（无法继续出库）",
          recall_res["issuableQty"] == 0, recall_res)
    st, blocked = http("POST", f"/api/incidents/{m2id}/material-scans/issue",
                       {"items": [{"batchNo": m2["plannedBatch"], "qty": 1, "lineNo": 1, "segmentCode": "S3"}]},
                       token=wh_token)
    check("召回批次再领用被 409 BATCH_NOT_ISSUABLE 拒绝（冻结/召回后不得出库）",
          st == 409 and blocked["code"] == "BATCH_NOT_ISSUABLE", (st, blocked))

    _, risk_after = http("GET", f"/api/materials/batches/{m2['plannedBatch']}/risk", token=wh_token, expect=200)
    check("召回风险清单：仍在库数量可查且可发数量=0",
          risk_after["inWarehouse"][0]["issuableQty"] == 0
          and risk_after["inWarehouse"][0]["status"] == "RECALLED", risk_after["inWarehouse"])
    check("召回风险清单：已领未装 2 件仍指向 M2 工单/管段/班组（可直接追责）",
          any(x["incidentId"] == m2id and x["segmentCode"] == "S3" and x["qty"] == 2
              for x in risk_after["issuedNotInstalled"]), risk_after["issuedNotInstalled"])

    # 已领未装还没退回时，仓库不能直接重做配料
    st, outstanding = http("POST", f"/api/incidents/{m2id}/material-provision/reprovision", {},
                           token=wh_token)
    check("已领未装未退回时重做配料被 409 MATERIAL_OUTSTANDING 拒绝",
          st == 409 and outstanding["code"] == "MATERIAL_OUTSTANDING", (st, outstanding))

    # 已领未装的旧件必须退回，不能装到管上；退回后仍不可发
    st, ret_m2 = http("POST", f"/api/incidents/{m2id}/material-scans/return",
                      {"items": [{"batchNo": m2["plannedBatch"], "qty": 2, "lineNo": 1, "segmentCode": "S3"}],
                       "reason": "批次召回，已领未装旧件退回", "eventAt": "2026-11-27T11:10:00+08:00"},
                      token=wh_token)
    check("已领未装的召回旧件扫码退回成功", st == 200, (st, ret_m2))
    _, b_after_return = http("GET", f"/api/materials/batches/{m2['plannedBatch']}", token=wh_token, expect=200)
    check("退回后批次仍不可发（召回批次即使回库也不显示为可发库存）",
          b_after_return["issuableQty"] == 0 and b_after_return["quantityOnHand"] >= 2,
          b_after_return)

    # 已领料后召回：退回未装旧件后，由仓库显式重做配料 -> 新配料单换用合格批次
    _, pv_m2_mid = http("GET", f"/api/incidents/{m2id}/material-provision", token=wh_token, expect=200)
    check("召回后原配料单进入 NEEDS_REPLACEMENT（已领后退回，等待重做）",
          pv_m2_mid["provision"]["status"] == "NEEDS_REPLACEMENT"
          and "BATCH_RECALLED" in (pv_m2_mid["provision"]["releaseReason"] or ""),
          pv_m2_mid["provision"])
    st, reprov_m2 = http("POST", f"/api/incidents/{m2id}/material-provision/reprovision", {},
                         token=wh_token)
    check("仓库显式重做配料成功，新配料单改用未召回的合格批次（不锁 Q1）",
          st == 200 and m2["plannedBatch"] not in reprov_m2["reveal"]["lockedBatches"]
          and bool(reprov_m2["reveal"]["lockedBatches"]), (st, reprov_m2["reveal"]))
    new_m2 = reprov_m2
    _, old_m2 = http("GET", f"/api/incidents/{m2id}/material-provisions", token=wh_token, expect=200)
    old_pv = next(p for p in old_m2 if p["provision"]["code"] == plan_m2["provision"]["code"])
    check("旧配料单标记 REPLACED 并保留召回原因与扫码历史",
          old_pv["provision"]["status"] == "REPLACED"
          and "BATCH_RECALLED" in (old_pv["provision"]["releaseReason"] or "")
          and any(sc["kind"] == "RETURN" for sc in old_pv["scans"]),
          old_pv["provision"])

    # 发车 + 领用替代批次 + 安装
    http("POST", f"/api/assignments/{a_m2['id']}/issue", {}, token=disp, expect=200)
    repl2 = [a["batchNo"] for a in new_m2["allocations"] if a["status"] == "ACTIVE"][0]
    st, iss_q2 = http("POST", f"/api/incidents/{m2id}/material-scans/issue",
                      {"items": [{"batchNo": repl2, "qty": 2, "lineNo": 1, "segmentCode": "S3"}],
                       "eventAt": "2026-11-27T11:12:00+08:00"}, token=wh_token)
    check(f"改用重做后的合格批次 {repl2} 领用成功", st == 200, (st, iss_q2))
    st, inst_q2 = http("POST", f"/api/incidents/{m2id}/material-scans/install",
                       {"item": {"batchNo": repl2, "qty": 2, "lineNo": 1,
                                 "segmentCode": "S3", "locationText": "S3 滨河巷5号阀后法兰"}},
                       token=crew_token)
    check("替代批次安装到 S3 并记录安装位置", st == 200 and inst_q2["segmentCode"] == "S3", (st, inst_q2))
    _, risk_after_install = http("GET", f"/api/materials/batches/{m2['plannedBatch']}/risk", token=wh_token, expect=200)
    check("召回批次风险清单：已领未装清零，历史已装记录为空（该批次从未装上）",
          not risk_after_install["issuedNotInstalled"] and not risk_after_install["installed"],
          risk_after_install)
    check("召回批次退回数量已记录（已退回件数在库）",
          risk_after_install["inWarehouse"][0]["quantityReturnedAfterRecall"] == 2,
          risk_after_install["inWarehouse"])

    # M2 回执并关单
    for tpl_type, t in m2["receipts"].items():
        body = {"assignmentId": a_m2["id"], "type": tpl_type, "eventAt": t}
        if tpl_type == "REPRESSURE":
            body["readingCode"] = m2["reading"]
        st, rc = http("POST", f"/api/incidents/{m2id}/receipts", body, token=disp)
        check(f"M2 {tpl_type} 回执成功", st == 200, (st, rc))

    # ---------- 18. 竣工验收：材料批次关联管段，形成验收依据 ----------
    section("18. 竣工验收把材料批次关联到管段；召回后能找出已安装材料去向")
    st, acc_m1 = http("POST", f"/api/incidents/{m1id}/material-acceptance",
                      {"note": "M1 竣工验收：管材批次按 S2 管段关联"}, token=disp)
    check("M1 验收成功：DN80/PN10 安装于 S2，DN100 安装于 S2（按管段关联）",
          st == 200 and acc_m1["basis"]["distinctSegments"] == ["S2"]
          and "PIPE-DN80-PN10-Q1" in acc_m1["basis"]["distinctBatches"]
          and "PIPE-DN100-PN16-Q1" in acc_m1["basis"]["distinctBatches"], (st, acc_m1))
    st, closed_m1 = http("POST", f"/api/incidents/{m1id}/close", {}, token=disp)
    check("M1 验收后关单成功", st == 200 and closed_m1["status"] == "CLOSED", (st, closed_m1))
    st, close_no_acc = http("POST", f"/api/incidents/{m2id}/close", {}, token=disp)
    check("M2 已安装材料但未验收 → 关单被 409 MATERIAL_ACCEPTANCE_REQUIRED 拒绝",
          st == 409 and close_no_acc["code"] == "MATERIAL_ACCEPTANCE_REQUIRED", (st, close_no_acc))
    st, acc_m2 = http("POST", f"/api/incidents/{m2id}/material-acceptance",
                      {"note": "M2 竣工验收：替代批次关联 S3"}, token=disp)
    check("M2 验收成功：重做后的合格批次关联 S3 管段",
          st == 200 and repl2 in acc_m2["basis"]["distinctBatches"]
          and acc_m2["basis"]["distinctSegments"] == ["S3"], (st, acc_m2))
    st, closed_m2 = http("POST", f"/api/incidents/{m2id}/close", {}, token=disp)
    check("M2 验收后关单成功", st == 200 and closed_m2["status"] == "CLOSED", (st, closed_m2))

    # ---------- 19. 两粒度对比：CATEGORY 先锁类别、装车前挑批次 ----------
    section("19. 对比 CATEGORY 粒度：先锁满足参数的材料类别，装车前按当时合格库存挑批次")
    _, r_m3 = http("POST", "/api/reports", {
        "hotlineCallId": "HC-20261127-1003", "callerPhone": "13900001003",
        "rawAddress": "滨河巷7号", "description": "S3 二次维护，按 CATEGORY 粒度配料验证",
        "receivedAt": "2026-11-27T12:10:00+08:00"}, token=disp, expect=200)
    _, inc_m3 = http("POST", "/api/incidents",
                     {"title": "S3 CATEGORY 粒度对比单", "reportIds": [r_m3["report"]["id"]],
                      "boundarySegmentCodes": ["S3"]}, token=disp, expect=200)
    m3id = inc_m3["id"]
    http("POST", f"/api/incidents/{m3id}/isolation-plans", {"valveCodes": ["V3"]}, token=disp, expect=200)
    st, plan_cat = http("POST", f"/api/incidents/{m3id}/repair-plans",
                        {"lines": [{"lineNo": 1, "category": "SEAL", "dn": 100, "material": "EPDM",
                                    "connectionType": "FLANGE", "pn": 16, "quantity": 2,
                                    "segmentCode": "S3", "locationText": "滨河巷7号阀后法兰"}],
                         "granularity": "CATEGORY", "approve": True,
                         "note": "只锁材料类别，装车前再挑批次"}, token=disp)
    _, pv_m3_before = http("GET", f"/api/incidents/{m3id}/material-provision", token=wh_token, expect=200)
    check("CATEGORY 粒度方案获批后只锁定材料类别，尚无具体批次",
          st == 200 and plan_cat["provision"]["granularity"] == "CATEGORY"
          and plan_cat["provision"]["status"] in ("LOCKED", "PENDING_LOCK")
          and not pv_m3_before["reveal"]["hasConcreteBatch"], pv_m3_before["reveal"])

    # 派工：CATEGORY 粒度在发车前按当时合格库存挑批次
    st, cat_disp = http("POST", f"/api/incidents/{m3id}/dispatch/repair",
                        {"crewCode": "C_SOLE", "strategy": "DIRECT",
                         "windowStart": "2026-11-27T12:30:00+08:00",
                         "windowEnd": "2026-11-27T14:00:00+08:00"}, token=disp)
    check("CATEGORY 粒度在派工发车前挑定具体合格批次（未再锁被召回的 Q1）",
          st == 200, (st, cat_disp))
    _, pv_m3 = http("GET", f"/api/incidents/{m3id}/material-provision", token=wh_token, expect=200)
    check("CATEGORY 粒度装车前挑中仍合格的 SEAL-DN100-PN16-Q2（避开已召回 Q1）",
          "SEAL-DN100-PN16-Q2" in pv_m3["reveal"]["lockedBatches"]
          and m2["plannedBatch"] not in pv_m3["reveal"]["lockedBatches"],
          pv_m3["reveal"])
    # 对比单完成对比后收回，释放队伍占用（不影响对比结论）
    http("POST", f"/api/assignments/{cat_disp['id']}/cancel",
         {"reason": "CATEGORY 对比验证完成，收回派工"}, token=disp, expect=200)

    # ---------- 19b. 召回先到：配料后、发车前即召回 ----------
    section("19b. 召回先到：配料后发车前召回，发车被拦，重做配料后正常发车")
    m4 = orders["recallFirst"]
    _, r_m4 = http("POST", "/api/reports", m4["report"], token=disp, expect=200)
    _, inc_m4 = http("POST", "/api/incidents",
                     {"title": m4["title"], "reportIds": [r_m4["report"]["id"]],
                      "boundarySegmentCodes": m4["boundary"]}, token=disp, expect=200)
    m4id = inc_m4["id"]
    http("POST", f"/api/incidents/{m4id}/isolation-plans", {"valveCodes": m4["valveCodes"]}, token=disp, expect=200)
    _, plan_m4 = http("POST", f"/api/incidents/{m4id}/repair-plans",
                      {"lines": m4["lines"], "granularity": "BATCH", "approve": True,
                       "preferredBatchNo": m4["plannedBatch"],
                       "note": "M4 配料锁 Q3，尚未领料/发车"}, token=disp, expect=200)
    _, pv_m4_before = http("GET", f"/api/incidents/{m4id}/material-provision", token=wh_token, expect=200)
    check("M4 配料先锁定 SEAL-DN100-PN16-Q3（尚未领料）",
          m4["plannedBatch"] in pv_m4_before["reveal"]["lockedBatches"], pv_m4_before["reveal"])

    recall_first = orders["recallFirstRecall"]
    st, rec4 = http("POST", "/api/materials/recalls",
                    {"batchNo": recall_first["batchNo"], "kind": recall_first["kind"],
                     "reason": recall_first["reason"], "publishedAt": recall_first["publishedAt"]},
                    token=wh_token)
    check("召回先到：批次 Q3 立即 RECALLED 且 issuableQty=0",
          st == 200 and rec4["batchStatus"] == "RECALLED" and rec4["issuableQty"] == 0, (st, rec4))
    _, pvs_m4 = http("GET", f"/api/incidents/{m4id}/material-provisions", token=wh_token, expect=200)
    check("召回后旧配料单被 REPLACED，并自动重做配料（新配料单改用合格批次 Q5/Q2）",
          pvs_m4[0]["provision"]["status"] == "REPLACED"
          and pvs_m4[-1]["provision"]["status"] in ("LOCKED", "PICKED")
          and m4["plannedBatch"] not in pvs_m4[-1]["reveal"]["lockedBatches"]
          and pvs_m4[-1]["reveal"]["lockedBatches"], pvs_m4)
    check("召回先到时没有任何已领未装/已安装记录（风险清单为空）",
          not rec4["issuedNotInstalled"] and not rec4["installed"], rec4)

    st, a_m4 = http("POST", f"/api/incidents/{m4id}/dispatch/repair",
                    {"crewCode": "C_SOLE", "strategy": "DIRECT",
                     "windowStart": m4["windowStart"], "windowEnd": m4["windowEnd"]},
                    token=disp, expect=200)
    _, pv_m4 = http("GET", f"/api/incidents/{m4id}/material-provision", token=wh_token, expect=200)
    rep_batch = [a["batchNo"] for a in pv_m4["allocations"] if a["status"] == "ACTIVE"]
    check("物料重做后方可发车，发车批次不含被召回 Q3",
          a_m4["status"] == "DISPATCHED" and m4["plannedBatch"] not in rep_batch, rep_batch)
    st, iss_m4 = http("POST", f"/api/incidents/{m4id}/material-scans/issue",
                      {"items": [{"batchNo": rep_batch[0], "qty": 2, "lineNo": 1, "segmentCode": "S1"}]},
                      token=wh_token, expect=200)
    st, inst_m4 = http("POST", f"/api/incidents/{m4id}/material-scans/install",
                       {"item": {"batchNo": rep_batch[0], "qty": 2, "lineNo": 1,
                                 "segmentCode": "S1", "locationText": "S1 建设路1号阀后法兰"}},
                       token=crew_token)
    check("替代批次安装到 S1", st == 200 and inst_m4["segmentCode"] == "S1", (st, inst_m4))
    for tpl_type, t in m4["receipts"].items():
        body = {"assignmentId": a_m4["id"], "type": tpl_type, "eventAt": t}
        if tpl_type == "REPRESSURE":
            body["readingCode"] = m4["reading"]
        st, rc = http("POST", f"/api/incidents/{m4id}/receipts", body, token=disp)
        check(f"M4 {tpl_type} 回执成功", st == 200, (st, rc))
    st, acc_m4 = http("POST", f"/api/incidents/{m4id}/material-acceptance", {}, token=disp, expect=200)
    st, closed_m4 = http("POST", f"/api/incidents/{m4id}/close", {}, token=disp)
    check("M4 验收并关单", st == 200 and closed_m4["status"] == "CLOSED", (st, closed_m4))
    _, risk_q3 = http("GET", f"/api/materials/batches/{m4['plannedBatch']}/risk", token=wh_token, expect=200)
    check("召回批次 Q3 风险清单无已装记录（从未装上），在库可发=0",
          not risk_q3["installed"] and risk_q3["inWarehouse"][0]["issuableQty"] == 0,
          risk_q3)

    # ---------- 19c. 已安装后召回：竣工验收已关联批次，召回后追溯去向并替换 ----------
    section("19c. 已安装后召回：验收已关联批次→召回追溯已装去向→替换保留历史并二次验收")
    m5 = orders["installedRecall"]
    _, r_m5 = http("POST", "/api/reports", m5["report"], token=disp, expect=200)
    _, inc_m5 = http("POST", "/api/incidents",
                     {"title": m5["title"], "reportIds": [r_m5["report"]["id"]],
                      "boundarySegmentCodes": m5["boundary"]}, token=disp, expect=200)
    m5id = inc_m5["id"]
    http("POST", f"/api/incidents/{m5id}/isolation-plans", {"valveCodes": m5["valveCodes"]}, token=disp, expect=200)
    _, plan_m5 = http("POST", f"/api/incidents/{m5id}/repair-plans",
                      {"lines": m5["lines"], "granularity": "BATCH", "approve": True,
                       "preferredBatchNo": m5["plannedBatch"], "note": "M5 锁定 Q4"}, token=disp, expect=200)
    st, a_m5 = http("POST", f"/api/incidents/{m5id}/dispatch/repair",
                    {"crewCode": "C_SOLE", "strategy": "DIRECT",
                     "windowStart": m5["windowStart"], "windowEnd": m5["windowEnd"]}, token=disp, expect=200)
    http("POST", f"/api/incidents/{m5id}/material-scans/issue",
         {"items": [{"batchNo": m5["plannedBatch"], "qty": 2, "lineNo": 1, "segmentCode": "S2"}]},
         token=wh_token, expect=200)
    st, inst_q4 = http("POST", f"/api/incidents/{m5id}/material-scans/install",
                       {"item": {"batchNo": m5["plannedBatch"], "qty": 2, "lineNo": 1,
                                 "segmentCode": "S2", "locationText": "S2 幸福路5号阀后法兰"}},
                       token=crew_token)
    check("M5 先安装 Q4 到 S2（此时尚未召回）", st == 200 and inst_q4["segmentCode"] == "S2", (st, inst_q4))
    for tpl_type, t in m5["receipts"].items():
        body = {"assignmentId": a_m5["id"], "type": tpl_type, "eventAt": t}
        if tpl_type == "REPRESSURE":
            body["readingCode"] = m5["reading"]
        st, rc = http("POST", f"/api/incidents/{m5id}/receipts", body, token=disp)
        check(f"M5 {tpl_type} 回执成功", st == 200, (st, rc))
    st, acc5_first = http("POST", f"/api/incidents/{m5id}/material-acceptance",
                          {"note": "M5 首次竣工验收：Q4 关联 S2"}, token=disp, expect=200)
    check("M5 首次竣工验收已把 Q4 批次关联到 S2 管段（验收依据）",
          m5["plannedBatch"] in acc5_first["basis"]["distinctBatches"]
          and acc5_first["basis"]["distinctSegments"] == ["S2"], (st, acc5_first))

    # 竣工验收后供应商发布认证撤销
    rec5 = orders["installedRecallRecall"]
    st, rec5_res = http("POST", "/api/materials/recalls",
                        {"batchNo": rec5["batchNo"], "kind": rec5["kind"],
                         "reason": rec5["reason"], "publishedAt": rec5["publishedAt"]},
                        token=wh_token)
    check("已装后认证撤销：批次 RECALLED 且可发数量=0",
          st == 200 and rec5_res["batchStatus"] == "RECALLED"
          and rec5_res["inWarehouse"][0]["issuableQty"] == 0, (st, rec5_res))
    check("风险清单能定位已安装材料具体去向：M5 工单 / S2 管段 / 2 件 / 安装扫码",
          len(rec5_res["installed"]) == 1
          and rec5_res["installed"][0]["incidentId"] == m5id
          and rec5_res["installed"][0]["assignmentId"] == a_m5["id"]
          and rec5_res["installed"][0]["segmentCode"] == "S2"
          and rec5_res["installed"][0]["qty"] == 2
          and rec5_res["installed"][0]["installScanId"] == inst_q4["id"]
          and rec5_res["installed"][0]["certStatus"] == "REVOKED", rec5_res["installed"])
    check("已安装的召回批次能反查竣工验收 id（验收依据与管段关联）",
          rec5_res["installed"][0]["acceptanceId"] == acc5_first["acceptance"]["id"],
          rec5_res["installed"])
    check("已安装的召回批次不在“已领未装”里（已装上，需替换而非退回）",
          not rec5_res["issuedNotInstalled"], rec5_res["issuedNotInstalled"])

    # 已装后召回：原配料单保留在役扫码并置 NEEDS_REPLACEMENT；由仓库重做配料取替换件
    _, pv5_before = http("GET", f"/api/incidents/{m5id}/material-provision", token=wh_token, expect=200)
    check("已装后召回：原配料单保留在役安装扫码并置 NEEDS_REPLACEMENT（不删历史）",
          pv5_before["provision"]["status"] == "NEEDS_REPLACEMENT"
          and any(sc["kind"] == "INSTALL" and sc["batchNo"] == m5["plannedBatch"]
                  for sc in pv5_before["scans"]), pv5_before["provision"])
    st, pv5 = http("POST", f"/api/incidents/{m5id}/material-provision/reprovision", {},
                   token=wh_token, expect=200)
    repl5 = [a["batchNo"] for a in pv5["allocations"] if a["status"] == "ACTIVE"]
    check("已装后召回由仓库重做配料，新配料单锁定的是未召回合格批次",
          bool(repl5) and m5["plannedBatch"] not in repl5, pv5["reveal"])
    repl5 = repl5[0]
    http("POST", f"/api/incidents/{m5id}/material-scans/issue",
         {"items": [{"batchNo": repl5, "qty": 2, "lineNo": 1, "segmentCode": "S2"}]},
         token=wh_token, expect=200)
    st, rep = http("POST", f"/api/incidents/{m5id}/material-scans/install",
                   {"item": {"batchNo": repl5, "qty": 2, "lineNo": 1,
                             "segmentCode": "S2", "locationText": "S2 幸福路5号阀后法兰（召回替换）",
                             "replacesScanId": inst_q4["id"]}}, token=crew_token)
    check("用未召回的合格批次替换已装 Q4（新安装扫码指向旧安装）",
          st == 200 and rep["replacesScanId"] == inst_q4["id"]
          and rep["batchNo"] == repl5, (st, rep))
    st, same = http("POST", f"/api/incidents/{m5id}/material-scans/issue",
                    {"items": [{"batchNo": m5["plannedBatch"], "qty": 1, "lineNo": 1, "segmentCode": "S2"}]},
                    token=wh_token)
    check("召回批次再领用继续被拒（即使已有替换）",
          st == 409 and same["code"] == "BATCH_NOT_ISSUABLE", (st, same))

    _, risk5_after = http("GET", f"/api/materials/batches/{m5['plannedBatch']}/risk", token=wh_token, expect=200)
    check("替换后风险清单：旧安装转入“已整改”，不再计为有效在役",
          len(risk5_after["installed"]) == 0
          and len(risk5_after["remediated"]) == 1
          and risk5_after["remediated"][0]["remediatedByScanId"] == rep["id"], risk5_after)
    check("已整改记录仍完整保留去向（工单/管段/数量/验收 id 全部不删除）",
          risk5_after["remediated"][0]["incidentId"] == m5id
          and risk5_after["remediated"][0]["segmentCode"] == "S2"
          and risk5_after["remediated"][0]["qty"] == 2
          and risk5_after["remediated"][0]["acceptanceId"] == acc5_first["acceptance"]["id"],
          risk5_after["remediated"][0])

    # 首次验收后又有替换安装 → 关单前必须重新验收（验收依据需与在役材料一致）
    st, reacc5 = http("POST", f"/api/incidents/{m5id}/close", {}, token=disp)
    check("首次验收后又发生替换安装 → 关单被 409 MATERIAL_REACCEPTANCE_REQUIRED 拒绝",
          st == 409 and reacc5["code"] == "MATERIAL_REACCEPTANCE_REQUIRED", (st, reacc5))
    # 二次验收：以替换后的合格批次为在役依据
    st, acc5 = http("POST", f"/api/incidents/{m5id}/material-acceptance",
                    {"note": "M5 二次验收：记录以召回替换后的合格批次为在役依据"}, token=disp)
    check("M5 二次验收依据只含替换后的合格批次安装于 S2（不把已召回 Q4 记作在役）",
          st == 200 and repl5 in acc5["basis"]["distinctBatches"]
          and m5["plannedBatch"] not in acc5["basis"]["distinctBatches"]
          and acc5["basis"]["distinctSegments"] == ["S2"], (st, acc5))
    st, closed5 = http("POST", f"/api/incidents/{m5id}/close", {}, token=disp)
    check("M5 替换并二次验收后正常关单", st == 200 and closed5["status"] == "CLOSED", (st, closed5))

    # ---------- 19d. 冻结批次：冻结后不得继续出库，已锁未领的旧件释放 ----------
    section("19d. 仓库冻结批次：冻结后不得继续出库，已锁未领的旧件释放锁定")
    st, frozen = http("POST", "/api/materials/batches/PIPE-DN80-PN16-Q1/freeze",
                      {"reason": "质检抽检不合格，同批停用"}, token=wh_token)
    check("冻结批次成功，状态 FROZEN 且可发数量=0",
          st == 200 and frozen["batchStatus"] == "FROZEN" and frozen["issuableQty"] == 0, (st, frozen))
    st, refreeze = http("POST", "/api/materials/batches/PIPE-DN80-PN16-Q1/freeze",
                        {"reason": "重复冻结"}, token=wh_token)
    check("重复冻结被 409 BATCH_NOT_QUALIFIED 拒绝", st == 409 and refreeze["code"] == "BATCH_NOT_QUALIFIED",
          (st, refreeze))
    _, b_frozen = http("GET", "/api/materials/batches/PIPE-DN80-PN16-Q1", token=wh_token, expect=200)
    check("冻结批次库存行仍保留但 issuableQty=0（不可发）",
          b_frozen["issuableQty"] == 0 and b_frozen["status"] == "FROZEN", b_frozen)
    # 冻结合格批次后，PN16 需求若只剩该批次，必须缺料，不得降级为 PN10
    _, fr_report = http("POST", "/api/reports", {
        "hotlineCallId": "HC-20261127-1997", "callerPhone": "13900001997",
        "rawAddress": "幸福路8号", "description": "冻结后缺料探针报修",
        "receivedAt": "2026-11-27T12:00:00+08:00"}, token=disp, expect=200)
    _, fr_inc = http("POST", "/api/incidents",
                     {"title": "冻结后缺料探针", "reportIds": [fr_report["report"]["id"]],
                      "boundarySegmentCodes": ["S2"]}, token=disp, expect=200)
    st, fr_plan = http("POST", f"/api/incidents/{fr_inc['id']}/repair-plans",
                       {"lines": [{"lineNo": 1, "category": "PIPE", "dn": 80, "material": "DUCTILE_IRON",
                                   "connectionType": "FLANGE", "pn": 16, "quantity": 6,
                                   "segmentCode": "S2", "locationText": "冻结后缺料探针"}],
                        "granularity": "BATCH", "approve": True}, token=disp)
    check("冻结最末 PN16 批次后，PN16 需求缺料（不会用 PN10 件顶替）",
          st == 409 and fr_plan["code"] == "MATERIAL_SHORTAGE", (st, fr_plan))

    # ---------- 20. 恢复后：未使用旧批次不显示为可发库存 ----------
    section("20. 恢复后旧批次核查：任何未使用旧批次均不显示为可发库存")
    st, final_batches = http("GET", "/api/materials/batches", token=wh_token, expect=200)
    old = next(b for b in final_batches if b["batchNo"] == m2["plannedBatch"])
    check("召回批次终态 RECALLED 且 issuableQty=0，在库数量仅作历史记录",
          old["status"] == "RECALLED" and old["issuableQty"] == 0, old)
    check("所有 issuableQty>0 的批次都是 QUALIFIED（冻结/召回批次不伪装可发）",
          all(b["status"] == "QUALIFIED" for b in final_batches if b["issuableQty"] > 0),
          [(b["batchNo"], b["status"], b["issuableQty"]) for b in final_batches])

    # ---------- 20b. 三种顺序 × 两种粒度的对比结论 ----------
    section("20b. 三种顺序×两种粒度：锁定、换料、退库、已装风险清单与验收依据对比")
    _, m1_final = http("GET", f"/api/incidents/{m1id}", token=disp, expect=200)
    _, m2_final = http("GET", f"/api/incidents/{m2id}", token=disp, expect=200)
    _, m4_final = http("GET", f"/api/incidents/{m4id}", token=disp, expect=200)
    _, cat_final = http("GET", f"/api/incidents/{catid}/material-provisions", token=wh_token, expect=200)

    # 配方变更先到：BATCH 需"释放旧件 + 重做配料"（两次配料单），CATEGORY 只改参数类别
    check("方案变更先到：BATCH 粒度经历 v1→v2 两张配料单",
          len([p for p in http("GET", f"/api/incidents/{m1id}/material-provisions", token=wh_token)[1]
               if p["provision"]["granularity"] == "BATCH"]) == 2)
    check("方案变更先到：CATEGORY 粒度同样 v1→v2，但获批不落具体批次",
          len(cat_final) == 2
          and all(p["provision"]["granularity"] == "CATEGORY" for p in cat_final))
    # 召回先到：批次一召回即不可发，重做后替换
    check("召回先到：Q3 从未进入扫码记录，重做配料后发车",
          not http("GET", f"/api/materials/batches/{m4['plannedBatch']}/risk", token=wh_token)[1]["installed"]
          and plan_m4["provision"]["id"] != http("GET", f"/api/incidents/{m4id}/material-provision", token=wh_token)[1]["provision"]["id"])
    # 已领料后召回：已领未装转退回，替代批次安装，验收仅含替代批次
    _, acc_m2_final = http("GET", f"/api/incidents/{m2id}/material-acceptance", token=disp, expect=200)
    check("已领料后召回：M2 验收依据只含重做后的合格批次，不含被召回 Q1",
          len(acc_m2_final) >= 1
          and repl2 in acc_m2_final[-1]["basis"]["distinctBatches"]
          and m2["plannedBatch"] not in acc_m2_final[-1]["basis"]["distinctBatches"],
          acc_m2_final[-1]["basis"] if acc_m2_final else None)
    _, risk5_final = http("GET", f"/api/materials/batches/{m5['plannedBatch']}/risk", token=wh_token, expect=200)
    check("已安装后召回：Q4 的已装去向保留为“已整改”，替换后无有效在役件",
          len(risk5_final["remediated"]) == 1 and not risk5_final["installed"]
          and risk5_final["inWarehouse"][0]["issuableQty"] == 0, risk5_final)

    # ---------- 21. 终态：所有占用均已释放 ----------
    section("21. 终态核对：抢修事故回执闭环 + 材料工单验收；资源占用释放")
    _, occ_all = http("GET", "/api/occupancies", token=disp)
    held_left = [o for o in occ_all if o["status"] == "HELD"]
    check("回放结束后不存在任何 HELD 占用（并发试探单已取消、各单已完成/关单）",
          not held_left, [(o["resourceType"], o["resourceCode"], o["incidentId"]) for o in held_left])
    _, incs = http("GET", "/api/incidents", token=disp)
    closed_codes = sorted(i["code"] for i in incs if i["status"] == "CLOSED")
    m1_state = next(i for i in incs if i["id"] == m1id)
    m2_state = next(i for i in incs if i["id"] == m2id)
    m5_state = next(i for i in incs if i["id"] == m5id)
    check("材料工单 M1/M2/M5 均已闭环并保存验收依据",
          m1_state["status"] == "CLOSED" and m2_state["status"] == "CLOSED"
          and m5_state["status"] == "CLOSED"
          and len(http("GET", f"/api/incidents/{m1id}/material-acceptance", token=disp)[1]) >= 1
          and len(http("GET", f"/api/incidents/{m2id}/material-acceptance", token=disp)[1]) >= 1
          and len(http("GET", f"/api/incidents/{m5id}/material-acceptance", token=disp)[1]) >= 2,
          [(m1_state["id"], m1_state["status"]), (m2_state["id"], m2_state["status"]),
           (m5_state["id"], m5_state["status"])])

    print("\n" + "#" * 78)
    print(f"# 回放断言结果：PASS={PASS_COUNT}  FAIL={FAIL_COUNT}")
    if FAIL_COUNT:
        print("# 失败项：")
        for line in FAIL_LINES:
            print("  - " + line)
        print("ALL_DONE: false")
        sys.exit(1)
    print("# 🎉 全部场景断言通过：同段重拨 / 相邻双支管 / 并发互斥 / 复压重派 / 更正链")
    print("ALL_DONE: true")
    sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("\n回放因关键断言失败中止:", e)
        print("ALL_DONE: false")
        sys.exit(1)
    except Exception as e:  # noqa
        import traceback
        traceback.print_exc()
        print("ALL_DONE: false")
        sys.exit(2)
