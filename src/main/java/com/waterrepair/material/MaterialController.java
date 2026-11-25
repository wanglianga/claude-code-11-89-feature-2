package com.waterrepair.material;

import com.waterrepair.auth.AuthService;
import com.waterrepair.auth.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 材料与仓库接口：维修方案（材料需求）、配料（两种粒度）、扫码（领用/退回/安装）、
 * 批次冻结/召回/认证撤销、风险清单与竣工验收。
 *
 * <p>角色：DISPATCHER 负责维修方案；WAREHOUSE 负责配料与出库；CREW 负责现场扫码；
 * 验收由 DISPATCHER/WAREHOUSE 执行。所有写接口在 Swagger 中标注在同一分组。</p>
 */
@Tag(name = "07-材料需求与仓库配料")
@RestController
public class MaterialController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MaterialService materialService;
    private final MaterialRecallService recallService;
    private final MaterialAcceptanceService acceptanceService;
    private final MaterialBatchRepository batchRepo;

    public MaterialController(MaterialService materialService,
                              MaterialRecallService recallService,
                              MaterialAcceptanceService acceptanceService,
                              MaterialBatchRepository batchRepo) {
        this.materialService = materialService;
        this.recallService = recallService;
        this.acceptanceService = acceptanceService;
        this.batchRepo = batchRepo;
    }

    // ---------------- 维修方案（材料需求） ----------------

    @Operation(summary = "下发维修方案：口径/材质/连接方式/额定压力/数量 → 仓库配料。变更即新版本并释放旧件")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/incidents/{id}/repair-plans")
    public MaterialDtos.PlanResult createPlan(@PathVariable Long id,
                                              @RequestBody MaterialDtos.CreateRepairPlanRequest request,
                                              HttpServletRequest http) {
        return materialService.createRepairPlan(id, request, operator(http));
    }

    @Operation(summary = "事故全部维修方案版本（含每次配料的锁定/释放轨迹）")
    @GetMapping("/api/incidents/{id}/repair-plans")
    public List<MaterialDtos.PlanResult> plans(@PathVariable Long id) {
        return materialService.planHistory(id);
    }

    @Operation(summary = "修理方案计算给仓库的需求快照（口径/材质/连接/额定压力/数量/安装管段）")
    @GetMapping("/api/incidents/{id}/material-requirements")
    public List<RepairPlanLine> requirements(@PathVariable Long id) {
        List<RepairPlanLine> all = new ArrayList<>();
        for (MaterialDtos.PlanResult r : materialService.planHistory(id)) {
            all.addAll(r.lines());
        }
        return all;
    }

    // ---------------- 配料单 ----------------

    @Operation(summary = "事故当前有效配料单（含方案参数、批次分配、扫码记录）")
    @GetMapping("/api/incidents/{id}/material-provision")
    public MaterialDtos.ProvisionView currentProvision(@PathVariable Long id) {
        return materialService.currentProvision(id);
    }

    @Operation(summary = "事故全部配料单历史（含被替换/释放的旧配料）")
    @GetMapping("/api/incidents/{id}/material-provisions")
    public List<MaterialDtos.ProvisionView> provisions(@PathVariable Long id) {
        return materialService.provisions(id);
    }

    @Operation(summary = "重做配料：方案变更/召回后由仓库显式新建配料单（旧配料与扫码历史保留）")
    @Roles({"WAREHOUSE", "DISPATCHER"})
    @PostMapping("/api/incidents/{id}/material-provision/reprovision")
    public MaterialDtos.ProvisionView reprovision(@PathVariable Long id, HttpServletRequest http) {
        return materialService.reprovision(id, operator(http));
    }

    @Operation(summary = "装车前按“当时合格库存”重新校验数量/证书/批次状态并挑定批次（CATEGORY 粒度使用）")
    @Roles({"WAREHOUSE", "DISPATCHER"})
    @PostMapping("/api/incidents/{id}/material-provision/pick")
    public MaterialDtos.ProvisionView pick(@PathVariable Long id, HttpServletRequest http) {
        return materialService.pick(id, operator(http));
    }

    // ---------------- 扫码 ----------------

    @Operation(summary = "现场扫码领用（按具体批次；冻结/召回批次拒绝出库；数量不可超锁定量）")
    @Roles({"WAREHOUSE", "CREW", "DISPATCHER"})
    @PostMapping("/api/incidents/{id}/material-scans/issue")
    public MaterialDtos.ProvisionView issue(@PathVariable Long id,
                                            @RequestBody MaterialDtos.IssueRequest request,
                                            HttpServletRequest http) {
        return materialService.issue(id, request, operator(http));
    }

    @Operation(summary = "现场扫码退回未用材料（退回后回补仓库数量；召回/冻结批次仍不可发）")
    @Roles({"WAREHOUSE", "CREW", "DISPATCHER"})
    @PostMapping("/api/incidents/{id}/material-scans/return")
    public MaterialDtos.ProvisionView returnMaterials(@PathVariable Long id,
                                                     @RequestBody MaterialDtos.ReturnRequest request,
                                                     HttpServletRequest http) {
        return materialService.returnMaterials(id, request, operator(http));
    }

    @Operation(summary = "现场扫码安装：记录实际安装批次+安装位置（管段）；支持以合格批次替换旧安装")
    @Roles({"CREW", "DISPATCHER"})
    @PostMapping("/api/incidents/{id}/material-scans/install")
    public MaterialScan install(@PathVariable Long id,
                                @RequestBody MaterialDtos.InstallRequest request,
                                HttpServletRequest http) {
        return materialService.install(id, request, operator(http));
    }

    @Operation(summary = "事故全部扫码事件（领用/退回/安装，含被替换与整改关系，历史不删除）")
    @GetMapping("/api/incidents/{id}/material-scans")
    public List<MaterialScan> scans(@PathVariable Long id) {
        return materialService.scansForIncident(id);
    }

    // ---------------- 批次库存与召回 ----------------

    @Operation(summary = "仓库合格批次库存（可发数量 issuableQty；召回/冻结后为 0；参数键用于核对等价性）")
    @GetMapping("/api/materials/batches")
    public List<MaterialDtos.BatchView> batches() {
        LocalDate today = LocalDate.now(ZONE);
        List<MaterialDtos.BatchView> views = new ArrayList<>();
        for (MaterialBatch b : batchRepo.findAll()) {
            views.add(batchView(b, today));
        }
        return views;
    }

    @Operation(summary = "指定批次的库存与可发数量")
    @GetMapping("/api/materials/batches/{batchNo}")
    public MaterialDtos.BatchView batch(@PathVariable String batchNo) {
        MaterialBatch b = batchRepo.findById(batchNo)
                .orElseThrow(() -> com.waterrepair.common.ApiException.notFound(
                        "BATCH_NOT_FOUND", "批次不存在: " + batchNo));
        return batchView(b, LocalDate.now(ZONE));
    }

    @Operation(summary = "供应商发布质量召回 / 饮用水认证撤销：批次立即不可发，并给出在库/已领未装/已安装去向")
    @Roles({"WAREHOUSE", "DISPATCHER"})
    @PostMapping("/api/materials/recalls")
    public MaterialDtos.RecallImpactView recall(@RequestBody MaterialDtos.RecallRequest request,
                                                HttpServletRequest http) {
        return recallService.recall(request, operator(http));
    }

    @Operation(summary = "仓库冻结批次（临时不可发，未使用旧件释放锁定后仍不可发）")
    @Roles({"WAREHOUSE", "DISPATCHER"})
    @PostMapping("/api/materials/batches/{batchNo}/freeze")
    public MaterialDtos.RecallImpactView freeze(@PathVariable String batchNo,
                                               @RequestBody(required = false) MaterialDtos.FreezeRequest request,
                                               HttpServletRequest http) {
        return recallService.freeze(batchNo, request == null ? null : request.reason(), operator(http));
    }

    @Operation(summary = "批次风险清单：仍在库、已领未装、已安装（含管段/工单/验收）与已整改")
    @GetMapping("/api/materials/batches/{batchNo}/risk")
    public MaterialDtos.RecallImpactView risk(@PathVariable String batchNo) {
        return recallService.impact(batchNo);
    }

    @Operation(summary = "全部召回记录")
    @GetMapping("/api/materials/recalls")
    public List<MaterialRecall> recalls() {
        return recallService.recallRecords();
    }

    // ---------------- 竣工验收 ----------------

    @Operation(summary = "竣工验收：把实际安装的材料批次按管段关联到工单，形成验收依据（关单前必须）")
    @Roles({"DISPATCHER", "WAREHOUSE"})
    @PostMapping("/api/incidents/{id}/material-acceptance")
    public MaterialDtos.AcceptanceView accept(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, Object> request,
                                             HttpServletRequest http) {
        String note = request == null ? null : String.valueOf(request.getOrDefault("note", ""));
        Object version = request == null ? null : request.get("planVersionNo");
        Integer planVersionNo = version instanceof Number n ? n.intValue()
                : version == null ? null : Integer.valueOf(String.valueOf(version));
        return acceptanceService.accept(id, note, operator(http), planVersionNo);
    }

    @Operation(summary = "工单验收依据（批次 × 管段 × 数量 × 认证与安装扫码）")
    @GetMapping("/api/incidents/{id}/material-acceptance")
    public List<MaterialDtos.AcceptanceView> acceptance(@PathVariable Long id) {
        return acceptanceService.list(id);
    }

    @Operation(summary = "按批次追溯所有已安装工单/管段（召回时回答“还在哪根管上”）")
    @GetMapping("/api/materials/batches/{batchNo}/installed")
    public MaterialDtos.RecallImpactView installed(@PathVariable String batchNo) {
        return recallService.impact(batchNo);
    }

    private MaterialDtos.BatchView batchView(MaterialBatch b, LocalDate today) {
        return new MaterialDtos.BatchView(
                b.getBatchNo(), b.getCategory(), b.getDn(), b.getMaterial(),
                b.getConnectionType(), b.getPn(), b.getQuantityTotal(), b.getQuantityOnHand(),
                b.getQuantityLocked(), b.issuableQty(today), b.qualified(today),
                b.certEffective(today), b.getCertNo(), b.getCertStatus(),
                b.getCertExpiry() == null ? null : b.getCertExpiry().toString(),
                b.getSupplierCode(), b.getSupplierName(), b.getManufacturer(),
                b.getWarehouseCode(), b.getShelfLocation(), b.getStatus(), b.getRecallId(),
                b.getStatusReason(),
                "DN" + b.getDn() + "/" + b.getMaterial() + "/" + b.getConnectionType()
                        + "/PN" + b.getPn());
    }

    private String operator(HttpServletRequest http) {
        return (String) http.getAttribute(AuthService.ATTR_USERNAME);
    }
}
