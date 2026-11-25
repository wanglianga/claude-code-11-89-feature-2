package com.waterrepair.material;

import com.waterrepair.common.ApiException;
import com.waterrepair.dispatch.Assignment;
import com.waterrepair.dispatch.AssignmentRepository;
import com.waterrepair.incident.Incident;
import com.waterrepair.incident.IncidentRepository;
import com.waterrepair.network.PipeSegmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 材料需求 → 配料（锁定）→ 现场扫码（领用/退回/安装）→ 验收的主流程。
 *
 * <p>两种配料粒度：</p>
 * <ul>
 *   <li><b>BATCH</b>：维修方案获批时即锁定具体合格批次，班组发车时物料确定、可追责；方案变更或批次召回后释放旧批次并重做配料。</li>
 *   <li><b>CATEGORY</b>：方案获批时只锁定满足参数的材料类别（口径/材质/连接方式/额定压力），
 *       装车前（派工单下达/暂留单下达）再按当时合格库存挑选具体批次，并在发料时重验数量、证书与批次状态。</li>
 * </ul>
 *
 * <p>硬约束：缺料时绝不把“同系列但口径/压力等级不符”的库存当等价品；冻结或召回批次不可出库；
 * 所有历史配料、扫码与验收记录只追加不删除。</p>
 */
@Service
public class MaterialService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final RepairPlanRepository planRepo;
    private final RepairPlanLineRepository planLineRepo;
    private final MaterialProvisionRepository provisionRepo;
    private final MaterialProvisionLineRepository provisionLineRepo;
    private final MaterialAllocationRepository allocationRepo;
    private final MaterialScanRepository scanRepo;
    private final MaterialBatchRepository batchRepo;
    private final MaterialRecallRepository recallRepo;
    private final IncidentRepository incidentRepo;
    private final AssignmentRepository assignmentRepo;
    private final PipeSegmentRepository segmentRepo;

    public MaterialService(RepairPlanRepository planRepo,
                           RepairPlanLineRepository planLineRepo,
                           MaterialProvisionRepository provisionRepo,
                           MaterialProvisionLineRepository provisionLineRepo,
                           MaterialAllocationRepository allocationRepo,
                           MaterialScanRepository scanRepo,
                           MaterialBatchRepository batchRepo,
                           MaterialRecallRepository recallRepo,
                           IncidentRepository incidentRepo,
                           AssignmentRepository assignmentRepo,
                           PipeSegmentRepository segmentRepo) {
        this.planRepo = planRepo;
        this.planLineRepo = planLineRepo;
        this.provisionRepo = provisionRepo;
        this.provisionLineRepo = provisionLineRepo;
        this.allocationRepo = allocationRepo;
        this.scanRepo = scanRepo;
        this.batchRepo = batchRepo;
        this.recallRepo = recallRepo;
        this.incidentRepo = incidentRepo;
        this.assignmentRepo = assignmentRepo;
        this.segmentRepo = segmentRepo;
    }

    // ------------------------------------------------------------------
    // 1. 维修方案（携带口径/材质/连接方式/额定压力需求）
    // ------------------------------------------------------------------

    @Transactional
    public MaterialDtos.PlanResult createRepairPlan(Long incidentId,
                                                    MaterialDtos.CreateRepairPlanRequest req,
                                                    String operator) {
        Incident incident = mustGetIncident(incidentId);
        if ("CLOSED".equals(incident.getStatus())) {
            throw ApiException.conflict("INCIDENT_CLOSED", "事故已结案，不能再下发或变更维修方案", null);
        }
        if (req.lines() == null || req.lines().isEmpty()) {
            throw ApiException.badRequest("PLAN_LINES_REQUIRED", "维修方案至少需要一行材料需求");
        }
        String granularity = normalizeGranularity(req.granularity());

        List<RepairPlan> history = planRepo.findByIncidentIdOrderByVersionNoAscIdAsc(incidentId);
        int nextVersion = history.size() + 1;

        // 参数校验（保留原始计算值，不对照管段设计值强行纠正——现场可能正是在纠正设计值）
        List<MaterialDtos.PlanLineRequest> validated = new ArrayList<>();
        for (MaterialDtos.PlanLineRequest line : req.lines()) {
            validated.add(validatePlanLine(line));
        }

        // 旧方案与当前有效配料单：方案变更 → 释放旧件并由仓库重做配料
        for (RepairPlan old : history) {
            if ("ACTIVE".equals(old.getStatus()) || "APPROVED".equals(old.getStatus())) {
                old.setStatus("SUPERSEDED");
                planRepo.save(old);
            }
        }
        MaterialProvision previous = activeProvision(incidentId).orElse(null);
        if (previous != null) {
            releaseProvision(previous, "PLAN_CHANGED_TO_V" + nextVersion, true, operator);
        }

        RepairPlan plan = new RepairPlan();
        plan.setIncidentId(incidentId);
        plan.setVersionNo(nextVersion);
        plan.setStatus(Boolean.FALSE.equals(req.approve()) ? "DRAFT" : "APPROVED");
        plan.setGranularity(granularity);
        plan.setNote(req.note());
        plan.setCreatedBy(operator);
        plan = planRepo.saveAndFlush(plan);

        List<RepairPlanLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (MaterialDtos.PlanLineRequest line : validated) {
            RepairPlanLine entity = new RepairPlanLine();
            entity.setPlanId(plan.getId());
            entity.setLineNo(lineNo++);
            entity.setCategory(line.category());
            entity.setDn(line.dn());
            entity.setMaterial(line.material());
            entity.setConnectionType(line.connectionType());
            entity.setPn(line.pn());
            entity.setQuantity(line.quantity());
            entity.setSegmentCode(line.segmentCode());
            entity.setLocationText(line.locationText());
            lines.add(planLineRepo.save(entity));
        }

        MaterialProvision provision = null;
        if ("APPROVED".equals(plan.getStatus())) {
            provision = provision(plan, lines, granularity,
                    previous == null ? null : previous.getId(), req.preferredBatchNo(), operator);
        }
        if (provision == null) {
            provision = provisionRepo.findFirstByIncidentIdOrderByIdDesc(incidentId).orElse(null);
        }
        return new MaterialDtos.PlanResult(plan, lines, provision);
    }

    @Transactional(readOnly = true)
    public List<MaterialDtos.PlanResult> planHistory(Long incidentId) {
        mustGetIncident(incidentId);
        List<MaterialDtos.PlanResult> results = new ArrayList<>();
        for (RepairPlan plan : planRepo.findByIncidentIdOrderByVersionNoAscIdAsc(incidentId)) {
            List<RepairPlanLine> lines = planLineRepo.findByPlanIdOrderByLineNoAscIdAsc(plan.getId());
            MaterialProvision provision = provisionRepo.findByIncidentIdOrderByIdAsc(incidentId).stream()
                    .filter(p -> plan.getId().equals(p.getPlanId()))
                    .max(Comparator.comparing(MaterialProvision::getId))
                    .orElse(null);
            results.add(new MaterialDtos.PlanResult(plan, lines, provision));
        }
        return results;
    }

    private MaterialDtos.PlanLineRequest validatePlanLine(MaterialDtos.PlanLineRequest line) {
        if (line == null) {
            throw ApiException.badRequest("PLAN_LINE_INVALID", "材料需求行不能为空");
        }
        String category = line.category() == null ? "" : line.category().trim().toUpperCase();
        if (category.isEmpty()) {
            throw ApiException.badRequest("PLAN_LINE_INVALID", "category 不能为空（如 PIPE/SEAL/COUPLING）");
        }
        if (line.dn() == null || line.dn() <= 0) {
            throw ApiException.badRequest("PLAN_LINE_INVALID", "口径 dn 必须为正整数（DN）");
        }
        String material = line.material() == null ? "" : line.material().trim().toUpperCase();
        if (material.isEmpty()) {
            throw ApiException.badRequest("PLAN_LINE_INVALID", "材质 material 不能为空");
        }
        String connection = line.connectionType() == null ? "" : line.connectionType().trim().toUpperCase();
        if (connection.isEmpty()) {
            throw ApiException.badRequest("PLAN_LINE_INVALID", "连接方式 connectionType 不能为空");
        }
        if (line.pn() == null || line.pn() <= 0) {
            throw ApiException.badRequest("PLAN_LINE_INVALID", "额定压力 pn 必须为正整数（PN）");
        }
        if (line.quantity() == null || line.quantity() <= 0) {
            throw ApiException.badRequest("PLAN_LINE_INVALID", "数量 quantity 必须为正整数");
        }
        if (line.segmentCode() == null || segmentRepo.findById(line.segmentCode()).isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_SEGMENT", "安装管段不存在: " + line.segmentCode());
        }
        return new MaterialDtos.PlanLineRequest(line.lineNo(), category, line.dn(), material,
                connection, line.pn(), line.quantity(), line.segmentCode(), line.locationText());
    }

    private String normalizeGranularity(String raw) {
        String g = raw == null ? "BATCH" : raw.trim().toUpperCase();
        if (!"BATCH".equals(g) && !"CATEGORY".equals(g)) {
            throw ApiException.badRequest("UNKNOWN_GRANULARITY",
                    "granularity 只能是 BATCH（锁具体批次）或 CATEGORY（先锁材料类别）");
        }
        return g;
    }

    // ------------------------------------------------------------------
    // 2. 配料单（锁定）
    // ------------------------------------------------------------------

    private MaterialProvision provision(RepairPlan plan, List<RepairPlanLine> planLines,
                                        String granularity, Long replacesId,
                                        String preferredBatchNo, String operator) {
        MaterialProvision provision = new MaterialProvision();
        provision.setIncidentId(plan.getIncidentId());
        provision.setPlanId(plan.getId());
        provision.setPlanVersionNo(plan.getVersionNo());
        provision.setGranularity(granularity);
        provision.setReplacesProvisionId(replacesId);
        provision.setStatus("PENDING_LOCK");
        provision.setCreatedBy(operator);
        provision.setNote("维修方案 v" + plan.getVersionNo() + " 获批，交由仓库配料");
        provision = provisionRepo.saveAndFlush(provision);
        provision.setCode(String.format("PV-%04d", provision.getId()));

        List<MaterialProvisionLine> lines = createProvisionLines(provision, planLines);

        // CATEGORY 粒度：只锁定“满足参数的材料类别”，先校验参数类别的合格库存存在
        if ("CATEGORY".equals(granularity)) {
            for (MaterialProvisionLine line : lines) {
                List<MaterialBatch> eligible = eligibleBatches(line, LocalDate.now(ZONE));
                int available = eligible.stream().mapToInt(MaterialBatch::getQuantityOnHand).sum();
                if (eligible.isEmpty() || available < line.getRequiredQty()) {
                    throw shortage(line, eligible);
                }
            }
            provision.setStatus("LOCKED");
            provision.setLockedAt(OffsetDateTime.now());
            return provisionRepo.save(provision);
        }

        // BATCH 粒度：立即锁定具体批次，数量以“当前合格且未冻结/未召回”的可用库存为限
        // preferredBatchNo 可跨行指定；只有参数匹配该行的行会用指定批次优先，其余行按批次号顺序挑。
        List<MaterialBatch> allEligible = new ArrayList<>();
        for (MaterialProvisionLine line : lines) {
            allEligible.addAll(eligibleBatches(line, LocalDate.now(ZONE)));
        }
        boolean requestedPreferred = preferredBatchNo != null && !preferredBatchNo.isBlank();
        if (requestedPreferred && allEligible.stream().noneMatch(b -> b.getBatchNo().equals(preferredBatchNo))) {
            throw ApiException.conflict("PREFERRED_BATCH_NOT_ELIGIBLE",
                    "指定批次 " + preferredBatchNo + " 不满足本方案任一行的参数或当前非合格可发",
                    Map.of("batchNo", preferredBatchNo));
        }
        List<MaterialAllocation> allocations = new ArrayList<>();
        for (MaterialProvisionLine line : lines) {
            List<MaterialBatch> eligible = eligibleBatches(line, LocalDate.now(ZONE));
            int need = line.getRequiredQty();
            if (eligible.stream().mapToInt(MaterialBatch::getQuantityOnHand).sum() < need) {
                throw shortage(line, eligible);
            }
            boolean preferredFitsLine = requestedPreferred
                    && eligible.stream().anyMatch(b -> b.getBatchNo().equals(preferredBatchNo));
            if (preferredFitsLine) {
                allocations.addAll(allocatePreferred(line, need, eligible, preferredBatchNo));
            } else {
                allocations.addAll(allocate(line, need, eligible));
            }
        }
        provision.setStatus("LOCKED");
        provision.setLockedAt(OffsetDateTime.now());
        provisionRepo.save(provision);
        return provision;
    }

    private List<MaterialProvisionLine> createProvisionLines(MaterialProvision provision,
                                                             List<RepairPlanLine> planLines) {
        List<MaterialProvisionLine> lines = new ArrayList<>();
        for (RepairPlanLine planLine : planLines) {
            MaterialProvisionLine line = new MaterialProvisionLine();
            line.setProvisionId(provision.getId());
            line.setLineNo(planLine.getLineNo());
            line.setPlanLineId(planLine.getId());
            line.setCategory(planLine.getCategory());
            line.setDn(planLine.getDn());
            line.setMaterial(planLine.getMaterial());
            line.setConnectionType(planLine.getConnectionType());
            line.setPn(planLine.getPn());
            line.setRequiredQty(planLine.getQuantity());
            line.setSegmentCode(planLine.getSegmentCode());
            line.setLocationText(planLine.getLocationText());
            lines.add(provisionLineRepo.save(line));
        }
        return lines;
    }

    /** 为一张已落库、尚未锁定的配料单按当前合格库存锁定（供召回后重做配料调用）。 */
    @Transactional
    public MaterialProvision lockFreshProvision(MaterialProvision provision, String operator) {
        LocalDate today = LocalDate.now(ZONE);
        List<MaterialProvisionLine> lines =
                provisionLineRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId());
        if ("CATEGORY".equals(provision.getGranularity())) {
            for (MaterialProvisionLine line : lines) {
                if (eligibleBatches(line, today).stream().mapToInt(MaterialBatch::getQuantityOnHand).sum()
                        < line.getRequiredQty()) {
                    throw shortage(line, eligibleBatches(line, today));
                }
            }
            provision.setStatus("LOCKED");
            provision.setLockedAt(OffsetDateTime.now());
            return provisionRepo.save(provision);
        }
        for (MaterialProvisionLine line : lines) {
            List<MaterialBatch> eligible = eligibleBatches(line, today);
            if (eligible.stream().mapToInt(MaterialBatch::getQuantityOnHand).sum() < line.getRequiredQty()) {
                throw shortage(line, eligible);
            }
            allocate(line, line.getRequiredQty(), eligible);
        }
        provision.setStatus("LOCKED");
        provision.setLockedAt(OffsetDateTime.now());
        return provisionRepo.save(provision);
    }

    /** 按批次号顺序从合格批次中挑选并锁定指定数量。 */
    private List<MaterialAllocation> allocate(MaterialProvisionLine line, int need,
                                              List<MaterialBatch> eligible) {
        List<MaterialAllocation> created = new ArrayList<>();
        int remaining = need;
        for (MaterialBatch batch : eligible) {
            if (remaining <= 0) {
                break;
            }
            MaterialBatch locked = batchRepo.findForUpdate(batch.getBatchNo()).orElse(batch);
            int usable = locked.issuableQty(LocalDate.now(ZONE));
            if (usable <= 0) {
                continue;
            }
            int take = Math.min(usable, remaining);
            locked.setQuantityLocked(locked.getQuantityLocked() + take);
            batchRepo.save(locked);

            MaterialAllocation allocation = new MaterialAllocation();
            allocation.setProvisionId(line.getProvisionId());
            allocation.setProvisionLineId(line.getId());
            allocation.setBatchNo(locked.getBatchNo());
            allocation.setAllocatedQty(take);
            allocation.setLineNo(line.getLineNo());
            created.add(allocationRepo.save(allocation));
            remaining -= take;
        }
        if (remaining > 0) {
            throw shortage(line, eligible);
        }
        return created;
    }

    /** 优先锁定指定批次（必须是满足参数的合格批次），不足的再从其余合格批次补齐。 */
    private List<MaterialAllocation> allocatePreferred(MaterialProvisionLine line, int need,
                                                       List<MaterialBatch> eligible,
                                                       String preferredBatchNo) {
        MaterialBatch preferred = eligible.stream()
                .filter(b -> b.getBatchNo().equals(preferredBatchNo))
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("PREFERRED_BATCH_NOT_ELIGIBLE",
                        "指定批次 " + preferredBatchNo + " 不满足该行参数或当前非合格可发",
                        Map.of("batchNo", preferredBatchNo, "lineNo", line.getLineNo(),
                                "dn", line.getDn(), "pn", line.getPn())));
        List<MaterialBatch> ordered = new ArrayList<>();
        ordered.add(preferred);
        for (MaterialBatch b : eligible) {
            if (!b.getBatchNo().equals(preferredBatchNo)) {
                ordered.add(b);
            }
        }
        return allocate(line, need, ordered);
    }

    private List<MaterialBatch> eligibleBatches(MaterialProvisionLine line, LocalDate today) {
        return batchRepo.findAll().stream()
                .filter(b -> b.qualified(today))
                .filter(b -> "ISSUABLE".equals(isEquivalent(line, b)))
                .sorted(Comparator.comparing(MaterialBatch::getBatchNo))
                .toList();
    }

    /**
     * 判断库存批次是否满足需求行参数。
     * 口径、材质、连接方式必须完全一致；额定压力只允许“不低于需求”，
     * 因此 DN100/PN10 不是 DN100/PN16 的等价件，不得用于替代。
     *
     * @return {@code "ISSUABLE"} 表示可直接作为该需求的合格件；否则返回首个不符原因。
     */
    public static String isEquivalent(MaterialProvisionLine line, MaterialBatch batch) {
        List<String> mismatches = equivalenceMismatches(line, batch);
        return mismatches.isEmpty() ? "ISSUABLE" : mismatches.get(0);
    }

    /** 逐项列出参数不符原因（空列表表示完全满足需求，可作为等价合格件）。 */
    public static List<String> equivalenceMismatches(MaterialProvisionLine line, MaterialBatch batch) {
        List<String> mismatches = new ArrayList<>();
        if (!line.getCategory().equals(batch.getCategory())) {
            mismatches.add("类别 " + batch.getCategory() + "≠需求 " + line.getCategory());
        }
        if (!Objects.equals(line.getDn(), batch.getDn())) {
            mismatches.add("口径 DN" + batch.getDn() + "≠需求 DN" + line.getDn());
        }
        if (!line.getMaterial().equals(batch.getMaterial())) {
            mismatches.add("材质 " + batch.getMaterial() + "≠需求 " + line.getMaterial());
        }
        if (!line.getConnectionType().equals(batch.getConnectionType())) {
            mismatches.add("连接 " + batch.getConnectionType() + "≠需求 " + line.getConnectionType());
        }
        if (batch.getPn() == null || batch.getPn() < line.getPn()) {
            mismatches.add("额定压力 PN" + batch.getPn() + " 低于需求 PN" + line.getPn());
        }
        return mismatches;
    }

    /**
     * 缺料：明确拒绝，并列出“同系列但参数不符”的近似库存（口径/材质/连接方式/压力等级任一不符），
     * 绝不把它们当等价品自动替代。
     */
    private ApiException shortage(MaterialProvisionLine line, List<MaterialBatch> eligible) {
        LocalDate today = LocalDate.now(ZONE);
        List<Map<String, Object>> nearMatches = new ArrayList<>();
        for (MaterialBatch b : batchRepo.findAll()) {
            if (!line.getCategory().equals(b.getCategory())) {
                continue;
            }
            List<String> mismatches = equivalenceMismatches(line, b);
            if (mismatches.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchNo", b.getBatchNo());
            m.put("qualified", b.qualified(today));
            m.put("issuableQty", b.issuableQty(today));
            m.put("mismatches", mismatches);
            nearMatches.add(m);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lineNo", line.getLineNo());
        details.put("category", line.getCategory());
        details.put("dn", line.getDn());
        details.put("material", line.getMaterial());
        details.put("connectionType", line.getConnectionType());
        details.put("pn", line.getPn());
        details.put("requiredQty", line.getRequiredQty());
        details.put("qualifiedEligibleQty", eligible.stream().mapToInt(MaterialBatch::getQuantityOnHand).sum());
        details.put("nearMatches", nearMatches);
        details.put("policy", "同系列但口径/材质/连接方式/额定压力不符的库存不得作为等价替代");
        return ApiException.conflict("MATERIAL_SHORTAGE",
                "仓库没有满足该行参数（DN" + line.getDn() + "/" + line.getMaterial() + "/"
                        + line.getConnectionType() + "/PN" + line.getPn() + "）的足量合格批次；"
                        + "不会用同系列近似件替代", details);
    }

    /** 释放配料单：把尚未发出的锁定数量退回仓库可用量，旧配料单保留为 REPLACED/RELEASED。 */
    @Transactional
    public int releaseProvision(MaterialProvision provision, String reason,
                                boolean replaced, String operator) {
        int released = 0;
        for (MaterialAllocation allocation : allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId())) {
            if (!"ACTIVE".equals(allocation.getStatus())) {
                continue;
            }
            int pending = allocation.getAllocatedQty() - allocation.getIssuedQty();
            if (pending > 0) {
                MaterialBatch batch = batchRepo.findForUpdate(allocation.getBatchNo()).orElse(null);
                if (batch != null) {
                    batch.setQuantityLocked(Math.max(0, batch.getQuantityLocked() - pending));
                    batchRepo.save(batch);
                }
                released += pending;
            }
            if (allocation.getIssuedQty() - allocation.getReturnedQty() - allocation.getInstalledQty() > 0) {
                // 已领未装部分仍允许现场退回/安装/替换，保留分配可操作；仅释放未发出的锁定量
                allocation.setReleaseReason(reason + "（未发出锁定量已释放，已领件待退回/替换）");
                allocationRepo.save(allocation);
            } else {
                allocation.setStatus("RELEASED");
                allocation.setReleaseReason(reason);
                allocationRepo.save(allocation);
            }
        }
        for (MaterialProvisionLine line : provisionLineRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId())) {
            if ("FULFILLED".equals(line.getStatus())) {
                continue; // 已足量领用的行保留状态供追溯
            }
            boolean hasOpen = allocationRepo.findByProvisionLineIdOrderByIdAsc(line.getId()).stream()
                    .anyMatch(a -> "ACTIVE".equals(a.getStatus())
                            && a.getIssuedQty() - a.getReturnedQty() - a.getInstalledQty() > 0);
            if (!hasOpen) {
                line.setStatus("RELEASED");
                provisionLineRepo.save(line);
            }
        }
        provision.setStatus(replaced ? "REPLACED" : "RELEASED");
        provision.setReleaseReason(reason);
        provision.setReleasedAt(OffsetDateTime.now());
        provisionRepo.save(provision);
        return released;
    }

    // ------------------------------------------------------------------
    // 3. 装车前挑批次 / 发料 / 退回 / 安装
    // ------------------------------------------------------------------

    @Transactional
    public MaterialDtos.ProvisionView currentProvision(Long incidentId) {
        mustGetIncident(incidentId);
        MaterialProvision provision = provisionRepo.findFirstByIncidentIdOrderByIdDesc(incidentId)
                .orElseThrow(() -> ApiException.notFound("NO_MATERIAL_PROVISION",
                        "该事故尚无维修方案配料单"));
        return view(provision);
    }

    /**
     * 重做配料：由仓库在方案变更/召回后显式发起，依据最新维修方案新建配料单。
     * 旧配料单（及其领用/安装历史）保留为 NEEDS_REPLACEMENT/REPLACED。
     */
    @Transactional
    public MaterialDtos.ProvisionView reprovision(Long incidentId, String operator) {
        mustGetIncident(incidentId);
        RepairPlan plan = planRepo.findFirstByIncidentIdOrderByVersionNoDescIdDesc(incidentId)
                .orElseThrow(() -> ApiException.notFound("NO_REPAIR_PLAN", "该事故尚无维修方案，无法重做配料"));
        if (!"APPROVED".equals(plan.getStatus())) {
            throw ApiException.conflict("REPAIR_PLAN_NOT_APPROVED",
                    "当前维修方案 v" + plan.getVersionNo() + " 未获批，不能重做配料", null);
        }
        List<MaterialProvision> history = provisionRepo.findByIncidentIdOrderByIdAsc(incidentId);
        MaterialProvision latest = history.isEmpty() ? null : history.get(history.size() - 1);
        // 任何未结配料若仍有“已领未装”件，必须先退回或替换，才能重做配料
        for (MaterialProvision open : history) {
            if (!List.of("LOCKED", "PICKED", "PARTIALLY_ISSUED", "ISSUED", "NEEDS_REPLACEMENT")
                    .contains(open.getStatus())) {
                continue;
            }
            boolean outstanding = allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(open.getId()).stream()
                    .anyMatch(a -> a.getIssuedQty() - a.getReturnedQty() - a.getInstalledQty() > 0);
            if (outstanding) {
                throw ApiException.conflict("MATERIAL_OUTSTANDING",
                        "配料 " + open.getCode() + " 仍有已领未装/在役材料，请先退回或替换后再重做配料",
                        Map.of("provisionCode", open.getCode(), "provisionId", open.getId()));
            }
        }
        List<RepairPlanLine> planLines = planLineRepo.findByPlanIdOrderByLineNoAscIdAsc(plan.getId());
        MaterialProvision provision = new MaterialProvision();
        provision.setIncidentId(incidentId);
        provision.setPlanId(plan.getId());
        provision.setPlanVersionNo(plan.getVersionNo());
        provision.setGranularity(plan.getGranularity() == null ? "BATCH" : plan.getGranularity());
        provision.setReplacesProvisionId(latest == null ? null : latest.getId());
        provision.setCreatedBy(operator);
        provision.setNote("仓库显式重做配料（承接方案 v" + plan.getVersionNo() + "）");
        provision.setStatus("PENDING_LOCK");
        provision = provisionRepo.saveAndFlush(provision);
        provision.setCode(String.format("PV-%04d", provision.getId()));
        createProvisionLines(provision, planLines);
        provision = lockFreshProvision(provision, operator);
        if (latest != null && List.of("NEEDS_REPLACEMENT", "LOCKED", "PARTIALLY_ISSUED")
                .contains(latest.getStatus())) {
            latest.setStatus("REPLACED");
            latest.setReleasedAt(OffsetDateTime.now());
            latest.setReleaseReason((latest.getReleaseReason() == null ? "" : latest.getReleaseReason())
                    + " → 已由 " + provision.getCode() + " 重做");
            provisionRepo.save(latest);
        }
        return view(provision);
    }

    @Transactional
    public MaterialDtos.ProvisionView pick(Long incidentId, String operator) {
        mustGetIncident(incidentId);
        MaterialProvision provision = activeProvision(incidentId)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_PROVISION",
                        "当前没有可装车的配料单（可能已释放/被替换/已关单）", null));
        provision = doPick(provision, true, operator);
        return view(provision);
    }

    /** 内部挑批次：CATEGORY 粒度在装车时按“当时合格库存”挑具体批次并重验数量/证书/状态。 */
    private MaterialProvision doPick(MaterialProvision provision, boolean strict, String operator) {
        if (!"CATEGORY".equals(provision.getGranularity())) {
            return provision;
        }
        if ("PICKED".equals(provision.getStatus()) || "PARTIALLY_ISSUED".equals(provision.getStatus())
                || "ISSUED".equals(provision.getStatus())) {
            // 幂等：若已有有效分配，仍以当时锁定批次为准；若批次已全部作废（召回/冻结），重新挑
            boolean active = allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId()).stream()
                    .anyMatch(a -> "ACTIVE".equals(a.getStatus()));
            if (active) {
                return provision;
            }
        }
        if (!List.of("LOCKED", "PENDING_LOCK", "NEEDS_REPLACEMENT").contains(provision.getStatus())) {
            throw ApiException.conflict("PROVISION_NOT_PICKABLE",
                    "配料单状态 " + provision.getStatus() + " 不允许挑批次", null);
        }
        LocalDate today = LocalDate.now(ZONE);
        for (MaterialProvisionLine line : provisionLineRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId())) {
            List<MaterialBatch> eligible = eligibleBatches(line, today);
            List<MaterialBatch> revalidated = new ArrayList<>();
            for (MaterialBatch b : eligible) {
                // 装车时重验：证书有效、批次未冻结/未召回、数量可用
                MaterialBatch fresh = batchRepo.findForUpdate(b.getBatchNo()).orElse(null);
                if (fresh != null && fresh.qualified(today) && fresh.certEffective(today)
                        && fresh.issuableQty(today) > 0) {
                    revalidated.add(fresh);
                }
            }
            if (revalidated.stream().mapToInt(MaterialBatch::getQuantityOnHand).sum() < line.getRequiredQty()) {
                if (strict) {
                    throw shortage(line, revalidated);
                }
                return provision;
            }
            allocate(line, line.getRequiredQty(), revalidated);
        }
        provision.setStatus("PICKED");
        provision.setPickedAt(OffsetDateTime.now());
        provision.setNote((provision.getNote() == null ? "" : provision.getNote())
                + " [装车前按 " + today + " 合格库存挑定具体批次]");
        return provisionRepo.save(provision);
    }

    /** 班组发车/派工单下达前，确保物料确定且合格；CATEGORY 粒度在此刻挑批次。 */
    @Transactional
    public void assertReadyForVehicle(Incident incident, Assignment assignment, String operator) {
        Optional<MaterialProvision> maybe = activeProvision(incident.getId());
        if (maybe.isEmpty()) {
            return; // 无材料方案的事故不受影响
        }
        MaterialProvision provision = maybe.get();
        if ("NEEDS_REPLACEMENT".equals(provision.getStatus())
                && !"CATEGORY".equals(provision.getGranularity())) {
            throw ApiException.conflict("MATERIAL_NEEDS_REPLACEMENT",
                "配料单 " + provision.getCode() + " 因方案变更/批次召回/冻结需要重做，不能发车", null);
        }
        if ("CATEGORY".equals(provision.getGranularity())
                && !List.of("PICKED", "PARTIALLY_ISSUED", "ISSUED").contains(provision.getStatus())) {
            provision = doPick(provision, true, operator);
        }
        verifyReady(provision, assignment);
        if (provision.getAssignmentId() == null) {
            provision.setAssignmentId(assignment.getId());
            provisionRepo.save(provision);
        }
    }

    private void verifyReady(MaterialProvision provision, Assignment assignment) {
        LocalDate today = LocalDate.now(ZONE);
        List<MaterialProvisionLine> lines = provisionLineRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId());
        List<MaterialAllocation> allocations = allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId());
        if (allocations.stream().noneMatch(a -> "ACTIVE".equals(a.getStatus()))) {
            throw ApiException.conflict("MATERIAL_NOT_PICKED",
                    "配料单 " + provision.getCode() + " 尚未挑定具体批次，不能发车", null);
        }
        for (MaterialProvisionLine line : lines) {
            int covered = allocations.stream()
                    .filter(a -> "ACTIVE".equals(a.getStatus()) && line.getId().equals(a.getProvisionLineId()))
                    .filter(a -> batchRepo.findById(a.getBatchNo()).map(b -> b.qualified(today) && b.certEffective(today)).orElse(false))
                    .mapToInt(a -> a.getAllocatedQty() - a.getReturnedQty())
                    .sum();
            if (covered < line.getRequiredQty() - installedForLine(line)) {
                throw ApiException.conflict("MATERIAL_NOT_READY",
                        "第 " + line.getLineNo() + " 行（" + line.getCategory() + " DN" + line.getDn()
                                + "/PN" + line.getPn() + "）当前没有足够的合格锁定批次，不能发车",
                        Map.of("lineNo", line.getLineNo(), "requiredQty", line.getRequiredQty(),
                                "coveredQty", covered, "provisionCode", provision.getCode(),
                                "assignmentCode", assignment == null ? "" : assignment.getCode()));
            }
        }
    }

    @Transactional
    public MaterialDtos.ProvisionView issue(Long incidentId, MaterialDtos.IssueRequest req, String operator) {
        Incident incident = mustGetIncident(incidentId);
        MaterialProvision provision = activeProvision(incidentId)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_PROVISION", "当前没有可发料的配料单", null));
        if (req.items() == null || req.items().isEmpty()) {
            throw ApiException.badRequest("ISSUE_ITEMS_REQUIRED", "领用扫码明细不能为空");
        }
        LocalDate today = LocalDate.now(ZONE);
        OffsetDateTime eventAt = req.eventAt() == null ? OffsetDateTime.now() : req.eventAt();
        for (MaterialDtos.ScanItem item : req.items()) {
            MaterialBatch batch = batchRepo.findForUpdate(item.batchNo())
                    .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + item.batchNo()));
            if (!batch.qualified(today) || !batch.certEffective(today)) {
                throw ApiException.conflict("BATCH_NOT_ISSUABLE",
                        "批次 " + batch.getBatchNo() + " 当前不可出库（状态=" + batch.getStatus()
                                + "，证书=" + batch.getCertStatus() + "）；冻结/召回批次不得继续出库",
                        Map.of("batchNo", batch.getBatchNo(), "status", batch.getStatus(),
                                "certStatus", batch.getCertStatus(), "recallId", batch.getRecallId()));
            }
            MaterialAllocation allocation = allocationRepo
                    .findByProvisionIdOrderByLineNoAscIdAsc(provision.getId()).stream()
                    .filter(a -> "ACTIVE".equals(a.getStatus()))
                    .filter(a -> a.getBatchNo().equals(batch.getBatchNo()))
                    .filter(a -> item.lineNo() == null || a.getLineNo().equals(item.lineNo()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.conflict("BATCH_NOT_ALLOCATED",
                            "批次 " + batch.getBatchNo() + " 不在配料单 " + provision.getCode() + " 的有效分配中",
                            Map.of("batchNo", batch.getBatchNo(), "provisionCode", provision.getCode())));
            int remaining = allocation.getAllocatedQty() - allocation.getIssuedQty();
            if (item.qty() == null || item.qty() <= 0 || item.qty() > remaining) {
                throw ApiException.conflict("ISSUE_QTY_INVALID",
                        "领用数量必须 >0 且不超过该分配剩余锁定量（剩余 " + remaining + "）",
                        Map.of("batchNo", batch.getBatchNo(), "lineNo", allocation.getLineNo(),
                                "remaining", remaining, "requested", item.qty()));
            }
            saveScan(provision, incident, allocation, MaterialScanKind.ISSUE, item.qty(),
                    item.segmentCode(), item.locationText(), eventAt, item.note(), null, operator);
            allocation.setIssuedQty(allocation.getIssuedQty() + item.qty());
            allocationRepo.save(allocation);
            batch.setQuantityOnHand(batch.getQuantityOnHand() - item.qty());
            batch.setQuantityLocked(Math.max(0, batch.getQuantityLocked() - item.qty()));
            batchRepo.save(batch);
        }
        refreshProvisionStatus(provision);
        refreshLineStatuses(provision);
        return view(provisionRepo.save(provision));
    }

    @Transactional
    public MaterialDtos.ProvisionView returnMaterials(Long incidentId, MaterialDtos.ReturnRequest req,
                                                      String operator) {
        mustGetIncident(incidentId);
        MaterialProvision provision = activeProvision(incidentId)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_PROVISION", "当前没有可退回的配料单", null));
        if (req.items() == null || req.items().isEmpty()) {
            throw ApiException.badRequest("RETURN_ITEMS_REQUIRED", "退回扫码明细不能为空");
        }
        OffsetDateTime eventAt = req.eventAt() == null ? OffsetDateTime.now() : req.eventAt();
        for (MaterialDtos.ScanItem item : req.items()) {
            MaterialAllocation allocation = findOpenAllocation(incidentId, item.batchNo(), item.lineNo(),
                            a -> a.getIssuedQty() - a.getReturnedQty() - a.getInstalledQty() > 0)
                    .orElseThrow(() -> ApiException.conflict("BATCH_NOT_ALLOCATED",
                            "批次 " + item.batchNo() + " 没有可退回的已领未用数量", null));
            int returnable = allocation.getIssuedQty() - allocation.getReturnedQty() - allocation.getInstalledQty();
            if (item.qty() == null || item.qty() <= 0 || item.qty() > returnable) {
                throw ApiException.conflict("RETURN_QTY_INVALID",
                        "退回数量必须 >0 且不超过已领未用数量（可退 " + returnable + "）",
                        Map.of("batchNo", item.batchNo(), "returnable", returnable,
                                "requested", item.qty()));
            }
            saveScan(provisionOf(allocation), mustGetIncident(incidentId), allocation, MaterialScanKind.RETURN,
                    item.qty(), item.segmentCode(), null, eventAt,
                    req.reason() == null ? item.note() : req.reason(), null, operator);
            allocation.setReturnedQty(allocation.getReturnedQty() + item.qty());
            allocationRepo.save(allocation);
            MaterialBatch batch = batchRepo.findForUpdate(item.batchNo()).orElse(null);
            if (batch != null) {
                // 退回后实物回到仓库可用量；若批次已召回/冻结，仍不可发（issuableQty=0）
                batch.setQuantityOnHand(batch.getQuantityOnHand() + item.qty());
                batchRepo.save(batch);
            }
        }
        return view(provision);
    }

    @Transactional
    public MaterialScan install(Long incidentId, MaterialDtos.InstallRequest req, String operator) {
        Incident incident = mustGetIncident(incidentId);
        MaterialDtos.ScanItem item = req.item();
        if (item == null || item.batchNo() == null || item.qty() == null || item.qty() <= 0) {
            throw ApiException.badRequest("INSTALL_ITEM_INVALID", "安装扫码必须给出 batchNo 与正数量 qty");
        }
        LocalDate today = LocalDate.now(ZONE);
        MaterialBatch batch = batchRepo.findById(item.batchNo())
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + item.batchNo()));
        if (!batch.qualified(today)) {
            throw ApiException.conflict("BATCH_NOT_INSTALLABLE",
                    "批次 " + batch.getBatchNo() + " 已冻结/召回/证书失效，不能新装；请使用合格替代批次",
                    Map.of("batchNo", batch.getBatchNo(), "status", batch.getStatus(),
                            "certStatus", batch.getCertStatus()));
        }
        MaterialScan replaced = null;
        if (item.replacesScanId() != null) {
            replaced = scanRepo.findById(item.replacesScanId())
                    .orElseThrow(() -> ApiException.notFound("SCAN_NOT_FOUND", "被替换的安装扫码不存在: " + item.replacesScanId()));
            if (replaced.getRemediatedByScanId() != null) {
                throw ApiException.conflict("SCAN_ALREADY_REPLACED", "该安装记录已被替换", null);
            }
            if (replaced.getBatchNo().equals(item.batchNo())) {
                throw ApiException.badRequest("REPLACE_SAME_BATCH", "替换安装必须换用不同批次");
            }
        }
        MaterialAllocation allocation = findOpenAllocation(incidentId, item.batchNo(), item.lineNo(),
                        a -> a.getIssuedQty() - a.getReturnedQty() - a.getInstalledQty() > 0)
                .orElseThrow(() -> ApiException.conflict("BATCH_NOT_ALLOCATED",
                        "批次 " + item.batchNo() + " 没有可安装的已领未装数量",
                        Map.of("batchNo", item.batchNo())));
        MaterialProvision provision = provisionOf(allocation);
        int installable = allocation.getIssuedQty() - allocation.getReturnedQty() - allocation.getInstalledQty();
        if (item.qty() > installable) {
            throw ApiException.conflict("INSTALL_QTY_EXCEEDS_ISSUED",
                    "安装数量超过已领用数量（已领未装 " + installable + "）",
                    Map.of("batchNo", item.batchNo(), "installable", installable, "requested", item.qty()));
        }
        String segment = item.segmentCode() != null ? item.segmentCode() : lineSegment(provision, allocation);
        MaterialScan scan = saveScan(provision, incident, allocation, MaterialScanKind.INSTALL,
                item.qty(), segment, item.locationText(), req.eventAt(), item.note(),
                item.replacesScanId(), operator);
        allocation.setInstalledQty(allocation.getInstalledQty() + item.qty());
        allocationRepo.save(allocation);
        if (replaced != null) {
            replaced.setRemediatedByScanId(scan.getId());
            scanRepo.save(replaced);
        }
        return scan;
    }

    private String lineSegment(MaterialProvision provision, MaterialAllocation allocation) {
        return provisionLineRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId()).stream()
                .filter(l -> l.getId().equals(allocation.getProvisionLineId()))
                .map(MaterialProvisionLine::getSegmentCode)
                .findFirst().orElse(null);
    }

    private MaterialScan saveScan(MaterialProvision provision, Incident incident,
                                  MaterialAllocation allocation, MaterialScanKind kind,
                                  Integer qty, String segmentCode, String locationText,
                                  OffsetDateTime eventAt, String note, Long replacesScanId,
                                  String operator) {
        MaterialScan scan = new MaterialScan();
        scan.setProvisionId(provision.getId());
        scan.setIncidentId(incident.getId());
        scan.setAssignmentId(provision.getAssignmentId());
        scan.setProvisionLineId(allocation.getProvisionLineId());
        scan.setBatchNo(allocation.getBatchNo());
        scan.setKind(kind.name());
        scan.setQty(qty);
        scan.setSegmentCode(segmentCode);
        scan.setLocationText(locationText);
        scan.setReplacesScanId(replacesScanId);
        scan.setNote(note);
        scan.setOperator(operator);
        scan.setEventAt(eventAt == null ? OffsetDateTime.now() : eventAt);
        return scanRepo.save(scan);
    }

    private Optional<MaterialAllocation> findOpenAllocation(Long incidentId, String batchNo,
                                                            Integer lineNo,
                                                            java.util.function.Predicate<MaterialAllocation> open) {
        List<MaterialProvision> provisions = provisionRepo.findByIncidentIdOrderByIdAsc(incidentId);
        for (MaterialProvision provision : provisions.reversed()) {
            for (MaterialAllocation a : allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId())) {
                if (!"ACTIVE".equals(a.getStatus()) && !"BLOCKED".equals(a.getStatus())) {
                    continue;
                }
                if (!a.getBatchNo().equals(batchNo)) {
                    continue;
                }
                if (lineNo != null && !a.getLineNo().equals(lineNo)) {
                    continue;
                }
                if (open.test(a)) {
                    return Optional.of(a);
                }
            }
        }
        return Optional.empty();
    }

    private MaterialProvision provisionOf(MaterialAllocation allocation) {
        return provisionRepo.findById(allocation.getProvisionId())
                .orElseThrow(() -> ApiException.notFound("PROVISION_NOT_FOUND",
                        "配料单不存在: " + allocation.getProvisionId()));
    }

    private void refreshProvisionStatus(MaterialProvision provision) {
        List<MaterialProvisionLine> lines = provisionLineRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId());
        List<MaterialAllocation> allocations = allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId());
        boolean all = true;
        boolean any = false;
        for (MaterialProvisionLine line : lines) {
            int netIssued = allocations.stream()
                    .filter(a -> line.getId().equals(a.getProvisionLineId()))
                    .mapToInt(a -> a.getIssuedQty() - a.getReturnedQty()).sum();
            if (netIssued >= line.getRequiredQty()) {
                any = true;
            } else {
                all = false;
            }
        }
        if (all) {
            provision.setStatus("ISSUED");
        } else if (any) {
            provision.setStatus("PARTIALLY_ISSUED");
        }
    }

    private void refreshLineStatuses(MaterialProvision provision) {
        List<MaterialAllocation> allocations = allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId());
        for (MaterialProvisionLine line : provisionLineRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId())) {
            int issued = allocations.stream()
                    .filter(a -> line.getId().equals(a.getProvisionLineId()))
                    .mapToInt(a -> a.getIssuedQty() - a.getReturnedQty()).sum();
            if (issued >= line.getRequiredQty() && !"RELEASED".equals(line.getStatus())) {
                line.setStatus("FULFILLED");
                provisionLineRepo.save(line);
            }
        }
    }

    private int installedForLine(MaterialProvisionLine line) {
        return scanRepo.findByProvisionLineIdOrderByIdAsc(line.getId()).stream()
                .filter(s -> "INSTALL".equals(s.getKind()) && s.getRemediatedByScanId() == null)
                .mapToInt(MaterialScan::getQty).sum();
    }

    // ------------------------------------------------------------------
    // 4. 供仓库/回放查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MaterialDtos.ProvisionView> provisions(Long incidentId) {
        mustGetIncident(incidentId);
        List<MaterialDtos.ProvisionView> views = new ArrayList<>();
        for (MaterialProvision p : provisionRepo.findByIncidentIdOrderByIdAsc(incidentId)) {
            views.add(view(p));
        }
        return views;
    }

    private MaterialDtos.ProvisionView view(MaterialProvision provision) {
        List<MaterialProvisionLine> lines =
                provisionLineRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId());
        List<MaterialAllocation> allocations =
                allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId());
        List<MaterialScan> scans = scanRepo.findByProvisionIdOrderByIdAsc(provision.getId());
        Map<String, Object> reveal = new LinkedHashMap<>();
        reveal.put("granularity", provision.getGranularity());
        reveal.put("status", provision.getStatus());
        reveal.put("hasConcreteBatch", allocations.stream().anyMatch(a -> "ACTIVE".equals(a.getStatus())));
        reveal.put("lockedBatches", allocations.stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .map(MaterialAllocation::getBatchNo).distinct().sorted().toList());
        reveal.put("blockedBatches", allocations.stream()
                .filter(a -> "BLOCKED".equals(a.getStatus()))
                .map(MaterialAllocation::getBatchNo).distinct().sorted().toList());
        reveal.put("issuedQty", allocations.stream().mapToInt(MaterialAllocation::getIssuedQty).sum());
        reveal.put("returnedQty", allocations.stream().mapToInt(MaterialAllocation::getReturnedQty).sum());
        reveal.put("installedQty", allocations.stream().mapToInt(MaterialAllocation::getInstalledQty).sum());
        reveal.put("pickNote", "CATEGORY".equals(provision.getGranularity())
                ? "CATEGORY 粒度：方案获批只锁参数类别，装车前按当时合格库存挑批次"
                : "BATCH 粒度：方案获批即锁定具体批次");
        return new MaterialDtos.ProvisionView(provision, lines, allocations, scans, reveal);
    }

    public Optional<MaterialProvision> activeProvision(Long incidentId) {
        List<MaterialProvision> all = provisionRepo.findByIncidentIdOrderByIdAsc(incidentId);
        for (MaterialProvision p : all.reversed()) {
            if (List.of("LOCKED", "PICKED", "PARTIALLY_ISSUED", "ISSUED", "NEEDS_REPLACEMENT").contains(p.getStatus())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public Incident mustGetIncident(Long incidentId) {
        return incidentRepo.findById(incidentId)
                .orElseThrow(() -> ApiException.notFound("INCIDENT_NOT_FOUND", "事故不存在: " + incidentId));
    }

    public MaterialProvision mustGetProvision(Long provisionId) {
        return provisionRepo.findById(provisionId)
                .orElseThrow(() -> ApiException.notFound("PROVISION_NOT_FOUND", "配料单不存在: " + provisionId));
    }

    public List<MaterialScan> scansForIncident(Long incidentId) {
        return scanRepo.findByIncidentIdOrderByIdAsc(incidentId);
    }

    /** 扫码事件类型。 */
    public enum MaterialScanKind {
        ISSUE, RETURN, INSTALL
    }
}
