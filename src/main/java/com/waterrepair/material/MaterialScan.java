package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 现场扫码事件：领用、退回、安装、召回后替换安装。只追加、不删除；
 * 召回后仍保留历史领用/安装记录，用于“已安装材料的具体去向”追溯。
 */
@Entity
@Table(name = "material_scan")
public class MaterialScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long provisionId;

    @Column(nullable = false)
    private Long incidentId;

    private Long assignmentId;

    @Column(nullable = false)
    private Long provisionLineId;

    @Column(nullable = false, length = 64)
    private String batchNo;

    /** ISSUE / RETURN / INSTALL / REPLACE */
    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false)
    private Integer qty;

    @Column(length = 32)
    private String segmentCode;

    @Column(length = 128)
    private String locationText;

    /** 替换安装时指向被替换的旧 INSTALL 扫码 */
    private Long replacesScanId;

    /** 旧安装扫码指向执行替换的新扫码（历史记录保留，同时可知已整改） */
    private Long remediatedByScanId;

    @Column(length = 512)
    private String note;

    @Column(length = 64)
    private String operator;

    @Column(nullable = false)
    private OffsetDateTime eventAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() {
        return id;
    }

    public Long getProvisionId() {
        return provisionId;
    }

    public void setProvisionId(Long provisionId) {
        this.provisionId = provisionId;
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

    public Long getProvisionLineId() {
        return provisionLineId;
    }

    public void setProvisionLineId(Long provisionLineId) {
        this.provisionLineId = provisionLineId;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public String getSegmentCode() {
        return segmentCode;
    }

    public void setSegmentCode(String segmentCode) {
        this.segmentCode = segmentCode;
    }

    public String getLocationText() {
        return locationText;
    }

    public void setLocationText(String locationText) {
        this.locationText = locationText;
    }

    public Long getReplacesScanId() {
        return replacesScanId;
    }

    public void setReplacesScanId(Long replacesScanId) {
        this.replacesScanId = replacesScanId;
    }

    public Long getRemediatedByScanId() {
        return remediatedByScanId;
    }

    public void setRemediatedByScanId(Long remediatedByScanId) {
        this.remediatedByScanId = remediatedByScanId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public OffsetDateTime getEventAt() {
        return eventAt;
    }

    public void setEventAt(OffsetDateTime eventAt) {
        this.eventAt = eventAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
