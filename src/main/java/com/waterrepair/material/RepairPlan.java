package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 维修方案：由维修方案计算出的口径、材质、连接方式、额定压力与数量，作为仓库配料的需求来源。
 * 每次现场改管径/改材质都新增版本，旧版本保留为 SUPERSEDED。
 */
@Entity
@Table(name = "repair_plan")
public class RepairPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long incidentId;

    @Column(nullable = false)
    private Integer versionNo;

    /** DRAFT / APPROVED / SUPERSEDED */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    /** BATCH（方案获批即锁具体批次）/ CATEGORY（先锁材料类别，装车前挑批次） */
    @Column(length = 16)
    private String granularity;

    @Column(length = 512)
    private String note;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() {
        return id;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(Long incidentId) {
        this.incidentId = incidentId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getGranularity() {
        return granularity;
    }

    public void setGranularity(String granularity) {
        this.granularity = granularity;
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
