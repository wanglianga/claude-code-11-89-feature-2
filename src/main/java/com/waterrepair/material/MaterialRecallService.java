package com.waterrepair.material;

import com.waterrepair.common.ApiException;
import com.waterrepair.dispatch.Assignment;
import com.waterrepair.dispatch.AssignmentRepository;
import com.waterrepair.incident.Incident;
import com.waterrepair.incident.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批次召回 / 冻结 / 认证撤销，以及召回后的风险清单（仍在库、已领未装、已安装/已整改）。
 *
 * <p>只有本服务能改变批次的可出库状态；任何未使用（未领/已领未装/已退回）的旧批次，
 * 召回或冻结后其 issuableQty 一律为 0，不会继续显示为可发库存。</p>
 */
@Service
public class MaterialRecallService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MaterialBatchRepository batchRepo;
    private final MaterialRecallRepository recallRepo;
    private final MaterialAllocationRepository allocationRepo;
    private final MaterialProvisionRepository provisionRepo;
    private final MaterialProvisionLineRepository provisionLineRepo;
    private final MaterialScanRepository scanRepo;
    private final RepairPlanRepository planRepo;
    private final RepairPlanLineRepository planLineRepo;
    private final IncidentRepository incidentRepo;
    private final AssignmentRepository assignmentRepo;
    private final AcceptanceItemRepository acceptanceItemRepo;
    private final MaterialService materialService;

    public MaterialRecallService(MaterialBatchRepository batchRepo,
                                 MaterialRecallRepository recallRepo,
                                 MaterialAllocationRepository allocationRepo,
                                 MaterialProvisionRepository provisionRepo,
                                 MaterialProvisionLineRepository provisionLineRepo,
                                 MaterialScanRepository scanRepo,
                                 RepairPlanRepository planRepo,
                                 RepairPlanLineRepository planLineRepo,
                                 IncidentRepository incidentRepo,
                                 AssignmentRepository assignmentRepo,
                                 AcceptanceItemRepository acceptanceItemRepo,
                                 MaterialService materialService) {
        this.batchRepo = batchRepo;
        this.recallRepo = recallRepo;
        this.allocationRepo = allocationRepo;
        this.provisionRepo = provisionRepo;
        this.provisionLineRepo = provisionLineRepo;
        this.scanRepo = scanRepo;
        this.planRepo = planRepo;
        this.planLineRepo = planLineRepo;
        this.incidentRepo = incidentRepo;
        this.assignmentRepo = assignmentRepo;
        this.acceptanceItemRepo = acceptanceItemRepo;
        this.materialService = materialService;
    }

    // ------------------------------------------------------------------
    // 召回 / 冻结
    // ------------------------------------------------------------------

    @Transactional
    public MaterialDtos.RecallImpactView recall(MaterialDtos.RecallRequest req, String operator) {
        if (req.batchNo() == null || req.batchNo().isBlank()) {
            throw ApiException.badRequest("BATCH_REQUIRED", "batchNo 不能为空");
        }
        MaterialBatch batch = batchRepo.findForUpdate(req.batchNo())
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + req.batchNo()));
        String kind = req.kind() == null ? "QUALITY_RECALL" : req.kind().trim().toUpperCase();
        if (!List.of("QUALITY_RECALL", "CERT_REVOCATION", "SUPPLIER_RECALL").contains(kind)) {
            throw ApiException.badRequest("UNKNOWN_RECALL_KIND",
                    "kind 必须是 QUALITY_RECALL / CERT_REVOCATION / SUPPLIER_RECALL");
        }

        MaterialRecall recall = new MaterialRecall();
        recall.setBatchNo(batch.getBatchNo());
        recall.setKind(kind);
        recall.setReason(req.reason() == null ? "供应商发布的召回/认证撤销" : req.reason());
        recall.setSupplierCode(batch.getSupplierCode());
        recall.setPublishedAt(req.publishedAt() == null ? OffsetDateTime.now() : req.publishedAt());
        recall.setCreatedBy(operator);
        recall = recallRepo.saveAndFlush(recall);

        batch.setStatus("RECALLED");
        if ("CERT_REVOCATION".equals(kind)) {
            batch.setCertStatus("REVOKED");
        }
        batch.setRecallId(recall.getId());
        batch.setStatusReason(kind + " r" + recall.getId() + ": " + recall.getReason());
        batchRepo.save(batch);

        List<Long> affectedProvisionIds = blockAllocationsOfBatch(batch,
                "BATCH_RECALLED r" + recall.getId());
        for (Long provisionId : affectedProvisionIds) {
            MaterialProvision provision = provisionRepo.findById(provisionId).orElse(null);
            if (provision == null) {
                continue;
            }
            handleProvisionAfterBatchLoss(provision, "BATCH_RECALLED r" + recall.getId(), operator);
        }
        return impact(batch.getBatchNo());
    }

    @Transactional
    public MaterialDtos.RecallImpactView freeze(String batchNo, String reason, String operator) {
        MaterialBatch batch = batchRepo.findForUpdate(batchNo)
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + batchNo));
        if (!"QUALIFIED".equals(batch.getStatus())) {
            throw ApiException.conflict("BATCH_NOT_QUALIFIED",
                    "只有状态为 QUALIFIED 的批次可以冻结，当前: " + batch.getStatus(), null);
        }
        batch.setStatus("FROZEN");
        batch.setStatusReason(reason == null ? "仓库冻结" : reason);
        batchRepo.save(batch);

        List<Long> affectedProvisionIds = blockAllocationsOfBatch(batch,
                "BATCH_FROZEN: " + (reason == null ? "" : reason));
        for (Long provisionId : affectedProvisionIds) {
            MaterialProvision provision = provisionRepo.findById(provisionId).orElse(null);
            if (provision != null) {
                handleProvisionAfterBatchLoss(provision,
                        "BATCH_FROZEN: " + (reason == null ? "" : reason), operator);
            }
        }
        return impact(batchNo);
    }

    /**
     * 把该批次在所有有效分配上的“未发出锁定量”退回仓库可用量，并把分配置 BLOCKED。
     * 返回受影响的配料单 id。
     */
    private List<Long> blockAllocationsOfBatch(MaterialBatch batch, String reason) {
        List<MaterialAllocation> allocations = allocationRepo.findByBatchNoOrderByIdAsc(batch.getBatchNo());
        Set<Long> provisionIds = new LinkedHashSet<>();
        int pending = 0;
        for (MaterialAllocation a : allocations) {
            if (!"ACTIVE".equals(a.getStatus())) {
                continue;
            }
            int outstanding = a.getAllocatedQty() - a.getIssuedQty();
            pending += Math.max(0, outstanding);
            a.setStatus("BLOCKED");
            a.setReleaseReason(reason);
            allocationRepo.save(a);
            provisionIds.add(a.getProvisionId());
        }
        batch.setQuantityLocked(Math.max(0, batch.getQuantityLocked() - pending));
        batchRepo.save(batch);
        return new ArrayList<>(provisionIds);
    }

    /**
     * 批次丢失后：
     * <ul>
     *   <li>BATCH 粒度：释放旧配料（旧件回仓但已不可发），并立即按当前合格库存重做一张新配料单。</li>
     *   <li>CATEGORY 粒度：作废已挑批次，配料单置 NEEDS_REPLACEMENT，等下次装车前重挑。</li>
     * </ul>
     * 重做若缺料，则新配料单明确置 NEEDS_REPLACEMENT，不静默降级。
     */
    private void handleProvisionAfterBatchLoss(MaterialProvision provision, String reason, String operator) {
        if (!List.of("LOCKED", "PICKED", "PARTIALLY_ISSUED", "ISSUED", "NEEDS_REPLACEMENT", "CLOSED")
                .contains(provision.getStatus())) {
            return;
        }
        if ("BATCH".equals(provision.getGranularity())) {
            // 已经发料/安装的旧配料不再自动重做（现场已用旧件，交给替换流程：退回未装的，替换已装的）
            boolean alreadyIssued = provisionLineRepo
                    .findByProvisionIdOrderByLineNoAscIdAsc(provision.getId()).stream()
                    .anyMatch(l -> "FULFILLED".equals(l.getStatus()))
                    || allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId()).stream()
                    .anyMatch(a -> a.getIssuedQty() > 0);
            if (alreadyIssued) {
                provision.setStatus("NEEDS_REPLACEMENT");
                provision.setReleaseReason(reason + " → 已有领用/安装，需现场退回未装件并替换在役件");
                provisionRepo.save(provision);
            } else if (!List.of("REPLACED", "RELEASED").contains(provision.getStatus())) {
                // 尚未领料：释放旧件并自动重做配料（旧配料单标记 REPLACED 保留历史）
                materialService.releaseProvision(provision, reason + " → 重做配料", true, operator);
                reProvision(provision, reason, operator);
            }
        } else {
            for (MaterialAllocation a : allocationRepo.findByProvisionIdOrderByLineNoAscIdAsc(provision.getId())) {
                if ("ACTIVE".equals(a.getStatus())) {
                    int outstanding = a.getAllocatedQty() - a.getIssuedQty();
                    MaterialBatch b = batchRepo.findById(a.getBatchNo()).orElse(null);
                    if (b != null && outstanding > 0) {
                        b.setQuantityLocked(Math.max(0, b.getQuantityLocked() - outstanding));
                        batchRepo.save(b);
                    }
                    a.setStatus("BLOCKED");
                    a.setReleaseReason(reason);
                    allocationRepo.save(a);
                }
            }
            provision.setStatus("NEEDS_REPLACEMENT");
            provision.setReleaseReason(reason + " → 需重新按合格库存挑批次");
            provisionRepo.save(provision);
        }
    }

    /** 依据旧配料单的维修方案重做一张新配料单（BATCH 粒度立即重新锁定）。 */
    private void reProvision(MaterialProvision old, String reason, String operator) {
        RepairPlan plan = planRepo.findById(old.getPlanId()).orElse(null);
        if (plan == null) {
            return;
        }
        List<RepairPlanLine> planLines = planLineRepo.findByPlanIdOrderByLineNoAscIdAsc(plan.getId());
        MaterialProvision provision = new MaterialProvision();
        provision.setIncidentId(old.getIncidentId());
        provision.setPlanId(plan.getId());
        provision.setPlanVersionNo(plan.getVersionNo());
        provision.setGranularity(old.getGranularity());
        provision.setReplacesProvisionId(old.getId());
        provision.setStatus("PENDING_LOCK");
        provision.setCreatedBy(operator);
        provision.setNote("因 " + reason + " 释放旧配料 " + old.getCode() + " 后重做配料");
        provision = provisionRepo.saveAndFlush(provision);
        provision.setCode(String.format("PV-%04d", provision.getId()));
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
            provisionLineRepo.save(line);
        }
        try {
            materialService.lockFreshProvision(provision, operator);
        } catch (ApiException ex) {
            provision.setStatus("NEEDS_REPLACEMENT");
            provision.setReleaseReason(reason + " 重做配料时缺料: " + ex.getMessage());
            provisionRepo.save(provision);
        }
    }

    // ------------------------------------------------------------------
    // 风险清单
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MaterialDtos.RecallImpactView impact(String batchNo) {
        MaterialBatch batch = batchRepo.findById(batchNo)
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + batchNo));
        LocalDate today = LocalDate.now(ZONE);

        List<MaterialDtos.IssuedNotInstalled> issuedNotInstalled = new ArrayList<>();
        List<MaterialDtos.InstalledItem> installed = new ArrayList<>();
        List<MaterialDtos.InstalledItem> remediated = new ArrayList<>();
        Set<Long> incidentIds = new LinkedHashSet<>();
        Set<Long> provisionIds = new LinkedHashSet<>();

        // 已领未装：按该批次的有效/阻塞分配逐条计算 issued - returned - installed
        for (MaterialAllocation a : allocationRepo.findByBatchNoOrderByIdAsc(batchNo)) {
            provisionIds.add(a.getProvisionId());
            int openQty = a.getIssuedQty() - a.getReturnedQty() - a.getInstalledQty();
            if (openQty <= 0) {
                continue;
            }
            MaterialProvision provision = provisionRepo.findById(a.getProvisionId()).orElse(null);
            MaterialProvisionLine line = provisionLineRepo.findById(a.getProvisionLineId()).orElse(null);
            Incident incident = provision == null ? null : incidentRepo.findById(provision.getIncidentId()).orElse(null);
            Assignment assignment = provision == null || provision.getAssignmentId() == null ? null
                    : assignmentRepo.findById(provision.getAssignmentId()).orElse(null);
            if (incident != null) {
                incidentIds.add(incident.getId());
            }
            issuedNotInstalled.add(new MaterialDtos.IssuedNotInstalled(
                    incident == null ? null : incident.getId(),
                    incident == null ? null : incident.getCode(),
                    assignment == null ? null : assignment.getId(),
                    assignment == null ? null : assignment.getCode(),
                    assignment == null ? null : assignment.getCrewCode(),
                    provision == null ? null : provision.getId(),
                    provision == null ? null : provision.getCode(),
                    line == null ? null : line.getLineNo(),
                    line == null ? null : line.getSegmentCode(),
                    line == null ? null : line.getLocationText(),
                    batchNo, openQty));
        }

        // 已安装：每条 INSTALL 扫码的管段/位置/验收关联；被替换的归入“已整改”
        for (MaterialScan s : scanRepo.findByKindAndBatchNoOrderByIdAsc("INSTALL", batchNo)) {
            provisionIds.add(s.getProvisionId());
            Incident incident = incidentRepo.findById(s.getIncidentId()).orElse(null);
            Assignment assignment = s.getAssignmentId() == null ? null
                    : assignmentRepo.findById(s.getAssignmentId()).orElse(null);
            if (incident != null) {
                incidentIds.add(incident.getId());
            }
            Long acceptanceId = acceptanceItemRepo.findByBatchNoOrderByIdAsc(batchNo).stream()
                    .filter(item -> item.getInstallScanId() != null && item.getInstallScanId().equals(s.getId()))
                    .map(AcceptanceItem::getAcceptanceId)
                    .findFirst().orElse(null);
            boolean isRemediated = s.getRemediatedByScanId() != null;
            MaterialDtos.InstalledItem item = new MaterialDtos.InstalledItem(
                    incident == null ? null : incident.getId(),
                    incident == null ? null : incident.getCode(),
                    assignment == null ? null : assignment.getId(),
                    assignment == null ? null : assignment.getCode(),
                    s.getSegmentCode(),
                    s.getLocationText(),
                    batchNo, s.getQty(), s.getId(), s.getReplacesScanId(),
                    s.getRemediatedByScanId(), isRemediated,
                    batch.getCertNo(), batch.getCertStatus(), acceptanceId);
            (isRemediated ? remediated : installed).add(item);
        }

        int returnedQty = scanRepo.findByBatchNoOrderByIdAsc(batchNo).stream()
                .filter(s -> "RETURN".equals(s.getKind()))
                .mapToInt(MaterialScan::getQty).sum();

        MaterialDtos.StillInWarehouse inWarehouse = new MaterialDtos.StillInWarehouse(
                batch.getBatchNo(), batch.getQuantityOnHand(), batch.getQuantityLocked(),
                returnedQty, batch.issuableQty(today), batch.getStatus(), batch.getStatusReason());

        return new MaterialDtos.RecallImpactView(batchNo, batch.getStatus(),
                batch.getQuantityOnHand(), batch.getQuantityLocked(), batch.issuableQty(today),
                List.of(inWarehouse), issuedNotInstalled, installed, remediated,
                provisionIds.stream().sorted().toList(), new ArrayList<>(incidentIds));
    }

    @Transactional(readOnly = true)
    public List<MaterialDtos.RecallImpactView> recalls() {
        List<MaterialDtos.RecallImpactView> list = new ArrayList<>();
        for (MaterialRecall r : recallRepo.findAllByOrderByIdAsc()) {
            list.add(impact(r.getBatchNo()));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<MaterialRecall> recallRecords() {
        return recallRepo.findAllByOrderByIdAsc();
    }
}
