package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 一次“给仓库的配料请求”（按某个维修方案版本）。每次方案变更或被召回后重做配料都会新增一行，
 * 旧配料单标记 RELEASED/REPLACED，不删除；它记录当时的粒度（BATCH/CATEGORY）与锁定的批次/类别。
 */
@Entity
@Table(name = "material_provision")
public class MaterialProvision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 32)
    private String code;

    @Column(nullable = false)
    private Long incidentId;

    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false)
    private Integer planVersionNo;

    private Long assignmentId;

    /** BATCH / CATEGORY */
    @Column(nullable = false, length = 16)
    private String granularity;

    /** PENDING_LOCK / LOCKED / NEEDS_REPLACEMENT / ISSUED / CLOSED / REPLACED / RELEASED */
    @Column(nullable = false, length = 24)
    private String status = "PENDING_LOCK";

    private Long replacesProvisionId;

    @Column(length = 512)
    private String releaseReason;

    private OffsetDateTime lockedAt;
    private OffsetDateTime pickedAt;
    private OffsetDateTime releasedAt;

    @Column(length = 512)
    private String note;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(Long incidentId) {
        this.incidentId = incidentId;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public Integer getPlanVersionNo() {
        return planVersionNo;
    }

    public void setPlanVersionNo(Integer planVersionNo) {
        this.planVersionNo = planVersionNo;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(Long assignmentId) {
        this.assignmentId = assignmentId;
    }

    public String getGranularity() {
        return granularity;
    }

    public void setGranularity(String granularity) {
        this.granularity = granularity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getReplacesProvisionId() {
        return replacesProvisionId;
    }

    public void setReplacesProvisionId(Long replacesProvisionId) {
        this.replacesProvisionId = replacesProvisionId;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    public void setReleaseReason(String releaseReason) {
        this.releaseReason = releaseReason;
    }

    public OffsetDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(OffsetDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public OffsetDateTime getPickedAt() {
        return pickedAt;
    }

    public void setPickedAt(OffsetDateTime pickedAt) {
        this.pickedAt = pickedAt;
    }

    public OffsetDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(OffsetDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
