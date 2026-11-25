package com.waterrepair.material;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 材料/仓库子域的请求与视图 DTO。 */
public final class MaterialDtos {

    private MaterialDtos() {
    }

    // ---------- 维修方案（材料需求） ----------

    public record PlanLineRequest(Integer lineNo,
                                  String category,
                                  Integer dn,
                                  String material,
                                  String connectionType,
                                  Integer pn,
                                  Integer quantity,
                                  String segmentCode,
                                  String locationText) {
    }

    public record CreateRepairPlanRequest(List<PlanLineRequest> lines,
                                          String granularity,
                                          Boolean approve,
                                          String note,
                                          /** 指定锁定的具体批次（可选；仅 BATCH 粒度；空则按批次号顺序挑合格批次） */
                                          String preferredBatchNo) {
    }

    public record PlanResult(RepairPlan plan,
                             List<RepairPlanLine> lines,
                             MaterialProvision provision) {
    }

    // ---------- 配料 / 批次 ----------

    public record BatchView(String batchNo,
                            String category,
                            Integer dn,
                            String material,
                            String connectionType,
                            Integer pn,
                            Integer quantityTotal,
                            Integer quantityOnHand,
                            Integer quantityLocked,
                            int issuableQty,
                            boolean qualified,
                            boolean certEffective,
                            String certNo,
                            String certStatus,
                            String certExpiry,
                            String supplierCode,
                            String supplierName,
                            String manufacturer,
                            String warehouseCode,
                            String shelfLocation,
                            String status,
                            Long recallId,
                            String statusReason,
                            String paramKey) {
    }

    public record ProvisionView(MaterialProvision provision,
                                List<MaterialProvisionLine> lines,
                                List<MaterialAllocation> allocations,
                                List<MaterialScan> scans,
                                Map<String, Object> reveal) {
    }

    public record ScanItem(String batchNo, Integer qty, Integer lineNo, String segmentCode,
                           String locationText, OffsetDateTime eventAt, String note,
                           Long replacesScanId) {
    }

    public record IssueRequest(List<ScanItem> items, OffsetDateTime eventAt) {
    }

    public record ReturnRequest(List<ScanItem> items, OffsetDateTime eventAt, String reason) {
    }

    public record InstallRequest(ScanItem item, OffsetDateTime eventAt) {
    }

    // ---------- 召回 / 冻结 ----------

    public record RecallRequest(String batchNo, String kind, String reason,
                                OffsetDateTime publishedAt, Boolean autoReprovision) {
    }

    public record FreezeRequest(String reason) {
    }

    public record RecallImpactView(String batchNo,
                                   String batchStatus,
                                   int quantityOnHand,
                                   int quantityLocked,
                                   int issuableQty,
                                   List<StillInWarehouse> inWarehouse,
                                   List<IssuedNotInstalled> issuedNotInstalled,
                                   List<InstalledItem> installed,
                                   List<InstalledItem> remediated,
                                   List<Long> affectedProvisionIds,
                                   List<Long> affectedIncidentIds) {
    }

    public record StillInWarehouse(String batchNo, int quantityOnHand, int quantityLocked,
                                   int quantityReturnedAfterRecall, int issuableQty,
                                   String status, String statusReason) {
    }

    public record IssuedNotInstalled(Long incidentId, String incidentCode,
                                     Long assignmentId, String assignmentCode, String crewCode,
                                     Long provisionId, String provisionCode, Integer lineNo,
                                     String segmentCode, String locationText,
                                     String batchNo, int qty) {
    }

    public record InstalledItem(Long incidentId, String incidentCode,
                                Long assignmentId, String assignmentCode,
                                String segmentCode, String locationText,
                                String batchNo, int qty, Long installScanId,
                                Long replacesScanId, Long remediatedByScanId,
                                boolean remediated, String certNo, String certStatus,
                                Long acceptanceId) {
    }

    // ---------- 竣工验收 ----------

    public record AcceptanceView(WorkAcceptance acceptance,
                                 List<AcceptanceItem> items,
                                 Map<String, Object> basis) {
    }
}
