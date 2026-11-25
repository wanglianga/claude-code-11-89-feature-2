package com.waterrepair.material;

import com.waterrepair.common.ApiException;
import com.waterrepair.incident.Incident;
import com.waterrepair.incident.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单竣工验收：把实际安装的材料批次按安装位置（管段）关联到工单，形成验收依据。
 * 验收明细是召回追溯“已安装材料具体去向”的权威来源，只追加不删除。
 */
@Service
public class MaterialAcceptanceService {

    private final IncidentRepository incidentRepo;
    private final MaterialScanRepository scanRepo;
    private final MaterialProvisionRepository provisionRepo;
    private final MaterialBatchRepository batchRepo;
    private final WorkAcceptanceRepository acceptanceRepo;
    private final AcceptanceItemRepository itemRepo;

    public MaterialAcceptanceService(IncidentRepository incidentRepo,
                                     MaterialScanRepository scanRepo,
                                     MaterialProvisionRepository provisionRepo,
                                     MaterialBatchRepository batchRepo,
                                     WorkAcceptanceRepository acceptanceRepo,
                                     AcceptanceItemRepository itemRepo) {
        this.incidentRepo = incidentRepo;
        this.scanRepo = scanRepo;
        this.provisionRepo = provisionRepo;
        this.batchRepo = batchRepo;
        this.acceptanceRepo = acceptanceRepo;
        this.itemRepo = itemRepo;
    }

    @Transactional
    public MaterialDtos.AcceptanceView accept(Long incidentId, String note, String operator,
                                              Integer planVersionNo) {
        Incident incident = incidentRepo.findById(incidentId)
                .orElseThrow(() -> ApiException.notFound("INCIDENT_NOT_FOUND", "事故不存在: " + incidentId));
        MaterialProvision provision = provisionRepo.findFirstByIncidentIdOrderByIdDesc(incidentId).orElse(null);
        if (provision == null) {
            throw ApiException.conflict("NO_MATERIAL_PROVISION", "该工单没有材料配料记录，无需材料验收", null);
        }
        if (planVersionNo != null) {
            provision = provisionRepo.findByIncidentIdOrderByIdAsc(incidentId).stream()
                    .filter(p -> planVersionNo.equals(p.getPlanVersionNo()))
                    .max(java.util.Comparator.comparing(MaterialProvision::getId))
                    .orElse(provision);
        }

        List<MaterialScan> installs = scanRepo.findByIncidentIdOrderByIdAsc(incidentId).stream()
                .filter(s -> "INSTALL".equals(s.getKind()))
                .filter(s -> s.getRemediatedByScanId() == null) // 已被替换的旧安装不计入有效验收
                .toList();
        if (installs.isEmpty()) {
            throw ApiException.conflict("NO_INSTALL_SCAN",
                    "尚无有效安装扫码（或安装均已被替换），不能形成材料验收依据", null);
        }

        WorkAcceptance acceptance = new WorkAcceptance();
        acceptance.setIncidentId(incidentId);
        acceptance.setProvisionId(provision.getId());
        acceptance.setPlanVersionNo(provision.getPlanVersionNo());
        acceptance.setInstalledItemCount(installs.stream().mapToInt(MaterialScan::getQty).sum());
        acceptance.setAcceptedBy(operator);
        acceptance.setBasisNote(note == null || note.isBlank()
                ? "按现场扫码安装记录形成验收依据：配料单 " + provision.getCode()
                + "（方案 v" + provision.getPlanVersionNo() + "）"
                : note);
        acceptance = acceptanceRepo.saveAndFlush(acceptance);

        for (MaterialScan scan : installs) {
            AcceptanceItem item = new AcceptanceItem();
            item.setAcceptanceId(acceptance.getId());
            item.setSegmentCode(scan.getSegmentCode());
            item.setBatchNo(scan.getBatchNo());
            item.setQty(scan.getQty());
            item.setInstallScanId(scan.getId());
            item.setLocationText(scan.getLocationText());
            MaterialBatch batch = batchRepo.findById(scan.getBatchNo()).orElse(null);
            if (batch != null) {
                item.setCategory(batch.getCategory());
                item.setCertNo(batch.getCertNo());
                item.setCertExpiry(batch.getCertExpiry());
                item.setCertStatus(batch.getCertStatus());
            } else {
                item.setCategory("UNKNOWN");
                item.setCertStatus("UNKNOWN");
            }
            itemRepo.save(item);
        }

        // 配料单收尾
        provision.setStatus("CLOSED");
        provisionRepo.save(provision);
        return view(acceptance);
    }

    @Transactional(readOnly = true)
    public MaterialDtos.AcceptanceView view(WorkAcceptance acceptance) {
        List<AcceptanceItem> items = itemRepo.findByAcceptanceIdOrderByIdAsc(acceptance.getId());
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("acceptanceId", acceptance.getId());
        basis.put("incidentId", acceptance.getIncidentId());
        basis.put("provisionId", acceptance.getProvisionId());
        basis.put("planVersionNo", acceptance.getPlanVersionNo());
        basis.put("installedItemCount", acceptance.getInstalledItemCount());
        basis.put("distinctBatches", items.stream().map(AcceptanceItem::getBatchNo)
                .distinct().sorted().toList());
        basis.put("distinctSegments", items.stream().map(AcceptanceItem::getSegmentCode)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList());
        Map<String, List<Map<String, Object>>> bySegment = new LinkedHashMap<>();
        for (AcceptanceItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchNo", item.getBatchNo());
            row.put("category", item.getCategory());
            row.put("qty", item.getQty());
            row.put("locationText", item.getLocationText());
            row.put("certNo", item.getCertNo());
            row.put("certStatus", item.getCertStatus());
            row.put("installScanId", item.getInstallScanId());
            bySegment.computeIfAbsent(item.getSegmentCode() == null ? "UNKNOWN" : item.getSegmentCode(),
                    k -> new ArrayList<>()).add(row);
        }
        basis.put("bySegment", bySegment);
        basis.put("status", acceptance.getStatus());
        basis.put("basisNote", acceptance.getBasisNote());
        basis.put("acceptedBy", acceptance.getAcceptedBy());
        basis.put("acceptedAt", acceptance.getAcceptedAt());
        return new MaterialDtos.AcceptanceView(acceptance, items, basis);
    }

    @Transactional(readOnly = true)
    public List<MaterialDtos.AcceptanceView> list(Long incidentId) {
        List<MaterialDtos.AcceptanceView> views = new ArrayList<>();
        for (WorkAcceptance a : acceptanceRepo.findByIncidentIdOrderByIdAsc(incidentId)) {
            views.add(view(a));
        }
        return views;
    }

    /**
     * 关单前校验：一旦有安装扫码，必须先形成材料验收依据；
     * 且验收之后若又发生安装/替换，必须重新验收，保证验收依据与实际在役材料一致。
     */
    @Transactional(readOnly = true)
    public void assertAcceptableBeforeClose(Long incidentId) {
        if (provisionRepo.findFirstByIncidentIdOrderByIdDesc(incidentId).isEmpty()) {
            return;
        }
        List<MaterialScan> installs = scanRepo.findByIncidentIdOrderByIdAsc(incidentId).stream()
                .filter(s -> "INSTALL".equals(s.getKind()))
                .toList();
        if (installs.isEmpty()) {
            return;
        }
        WorkAcceptance latest = acceptanceRepo
                .findFirstByIncidentIdAndStatusOrderByIdDesc(incidentId, "ACCEPTED").orElse(null);
        if (latest == null) {
            throw ApiException.conflict("MATERIAL_ACCEPTANCE_REQUIRED",
                    "该工单已安装材料但尚未竣工验收（材料批次尚未关联到管段），不能关单",
                    Map.of("installScans", installs.size(),
                            "distinctBatches", Set.copyOf(installs.stream()
                                    .map(MaterialScan::getBatchNo).toList())));
        }
        OffsetDateTime lastInstallAt = installs.stream()
                .map(MaterialScan::getCreatedAt)
                .max(java.util.Comparator.naturalOrder())
                .orElseThrow();
        if (lastInstallAt.isAfter(latest.getAcceptedAt())) {
            throw ApiException.conflict("MATERIAL_REACCEPTANCE_REQUIRED",
                    "上次验收后又有新的安装/替换扫码；请重新竣工验收，把实际在役批次重新关联到管段后再关单",
                    Map.of("lastAcceptanceId", latest.getId(),
                            "lastInstallAt", lastInstallAt.toString(),
                            "lastAcceptedAt", latest.getAcceptedAt().toString()));
        }
    }
}
