package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 工单（事故）竣工验收记录：把实际安装的材料批次按其安装位置（管段）关联起来，
 * 形成“验收依据”。这是供应商召回/认证撤销后追溯“已安装材料”的权威来源。
 */
@Entity
@Table(name = "work_acceptance")
public class WorkAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long incidentId;

    private Long assignmentId;

    private Long provisionId;

    private Integer planVersionNo;

    /** ACCEPTED / VOIDED */
    @Column(nullable = false, length = 16)
    private String status = "ACCEPTED";

    @Column(nullable = false)
    private Integer installedItemCount;

    @Column(length = 512)
    private String basisNote;

    @Column(length = 64)
    private String acceptedBy;

    @Column(nullable = false)
    private OffsetDateTime acceptedAt = OffsetDateTime.now();

    public Long getId() {
        return id;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(Long incidentId) {
        this.incidentId = incidentId;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(Long assignmentId) {
        this.assignmentId = assignmentId;
    }

    public Long getProvisionId() {
        return provisionId;
    }

    public void setProvisionId(Long provisionId) {
        this.provisionId = provisionId;
    }

    public Integer getPlanVersionNo() {
        return planVersionNo;
    }

    public void setPlanVersionNo(Integer planVersionNo) {
        this.planVersionNo = planVersionNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getInstalledItemCount() {
        return installedItemCount;
    }

    public void setInstalledItemCount(Integer installedItemCount) {
        this.installedItemCount = installedItemCount;
    }

    public String getBasisNote() {
        return basisNote;
    }

    public void setBasisNote(String basisNote) {
        this.basisNote = basisNote;
    }

    public String getAcceptedBy() {
        return acceptedBy;
    }

    public void setAcceptedBy(String acceptedBy) {
        this.acceptedBy = acceptedBy;
    }

    public OffsetDateTime getAcceptedAt() {
        return acceptedAt;
    }
}
