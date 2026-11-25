package com.waterrepair.incident;

import com.waterrepair.dispatch.Assignment;
import com.waterrepair.dispatch.AssignmentRepository;
import com.waterrepair.dispatch.ResourceOccupancy;
import com.waterrepair.dispatch.ResourceOccupancyRepository;
import com.waterrepair.receipt.FieldReceipt;
import com.waterrepair.material.MaterialAcceptanceService;
import com.waterrepair.material.MaterialProvision;
import com.waterrepair.material.MaterialProvisionRepository;
import com.waterrepair.material.MaterialScan;
import com.waterrepair.material.MaterialScanRepository;
import com.waterrepair.material.MaterialDtos;
import com.waterrepair.material.RepairPlan;
import com.waterrepair.material.RepairPlanLine;
import com.waterrepair.material.RepairPlanLineRepository;
import com.waterrepair.material.RepairPlanRepository;
import com.waterrepair.receipt.FieldReceiptRepository;
import com.waterrepair.report.ReportDtos;
import com.waterrepair.report.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 事故全状态聚合：原文线索与归属判断、方案版本、通知链、更正链、
 * 派工单、占用记录、历史回执，一次返回供回放核对。
 */
@Service
public class IncidentQueryService {

    private final IncidentRepository incidentRepo;
    private final IncidentSourceRepository sourceRepo;
    private final IsolationPlanRepository planRepo;
    private final NotificationRecordRepository notificationRepo;
    private final CorrectionRecordRepository correctionRepo;
    private final PressureReviewRepository reviewRepo;
    private final AssignmentRepository assignmentRepo;
    private final ResourceOccupancyRepository occupancyRepo;
    private final FieldReceiptRepository receiptRepo;
    private final ReportService reportService;
    private final RepairPlanRepository repairPlanRepo;
    private final RepairPlanLineRepository repairPlanLineRepo;
    private final MaterialProvisionRepository provisionRepo;
    private final MaterialScanRepository materialScanRepo;
    private final MaterialAcceptanceService acceptanceService;

    public IncidentQueryService(IncidentRepository incidentRepo,
                                IncidentSourceRepository sourceRepo,
                                IsolationPlanRepository planRepo,
                                NotificationRecordRepository notificationRepo,
                                CorrectionRecordRepository correctionRepo,
                                PressureReviewRepository reviewRepo,
                                AssignmentRepository assignmentRepo,
                                ResourceOccupancyRepository occupancyRepo,
                                FieldReceiptRepository receiptRepo,
                                ReportService reportService,
                                RepairPlanRepository repairPlanRepo,
                                RepairPlanLineRepository repairPlanLineRepo,
                                MaterialProvisionRepository provisionRepo,
                                MaterialScanRepository materialScanRepo,
                                MaterialAcceptanceService acceptanceService) {
        this.incidentRepo = incidentRepo;
        this.sourceRepo = sourceRepo;
        this.planRepo = planRepo;
        this.notificationRepo = notificationRepo;
        this.correctionRepo = correctionRepo;
        this.reviewRepo = reviewRepo;
        this.assignmentRepo = assignmentRepo;
        this.occupancyRepo = occupancyRepo;
        this.receiptRepo = receiptRepo;
        this.reportService = reportService;
        this.repairPlanRepo = repairPlanRepo;
        this.repairPlanLineRepo = repairPlanLineRepo;
        this.provisionRepo = provisionRepo;
        this.materialScanRepo = materialScanRepo;
        this.acceptanceService = acceptanceService;
    }

    public record IncidentDetail(Incident incident,
                                 List<IncidentSource> sources,
                                 List<ReportDtos.ReportDetail> reportDetails,
                                 List<IsolationPlan> plans,
                                 List<NotificationRecord> notifications,
                                 List<CorrectionRecord> corrections,
                                 List<PressureReview> pressureReviews,
                                 List<Assignment> assignments,
                                 List<ResourceOccupancy> occupancies,
                                 List<FieldReceipt> receipts,
                                 List<RepairPlan> repairPlans,
                                 List<MaterialProvision> materialProvisions,
                                 List<MaterialScan> materialScans,
                                 List<MaterialDtos.AcceptanceView> materialAcceptances) {
    }

    @Transactional(readOnly = true)
    public IncidentDetail detail(Long id) {
        Incident incident = incidentRepo.findById(id)
                .orElseThrow(() -> new com.waterrepair.common.ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "INCIDENT_NOT_FOUND", "事故不存在: " + id));
        List<IncidentSource> sources = sourceRepo.findByIncidentId(id);
        List<ReportDtos.ReportDetail> reports = new ArrayList<>();
        for (IncidentSource s : sources) {
            reports.add(reportService.detail(s.getReportId()));
        }
        List<Assignment> assignments = assignmentRepo.findByIncidentIdOrderByIdAsc(id);
        List<ResourceOccupancy> occupancies = occupancyRepo.findByIncidentIdOrderByIdAsc(id);
        List<FieldReceipt> receipts = receiptRepo.findByIncidentIdOrderByIdAsc(id);
        List<RepairPlan> repairPlans = repairPlanRepo.findByIncidentIdOrderByVersionNoAscIdAsc(id);
        List<MaterialProvision> provisionList = provisionRepo.findByIncidentIdOrderByIdAsc(id);
        List<MaterialScan> materialScans = materialScanRepo.findByIncidentIdOrderByIdAsc(id);
        List<MaterialDtos.AcceptanceView> acceptances = acceptanceService.list(id);
        return new IncidentDetail(
                incident,
                sources,
                reports,
                planRepo.findByIncidentIdOrderByVersionNoAscIdAsc(id),
                notificationRepo.findByIncidentIdOrderByIdAsc(id),
                correctionRepo.findByIncidentIdOrderByRevisionAscIdAsc(id),
                reviewRepo.findByIncidentIdOrderByIdAsc(id),
                assignments,
                occupancies,
                receipts,
                repairPlans,
                provisionList,
                materialScans,
                acceptances);
    }
}
