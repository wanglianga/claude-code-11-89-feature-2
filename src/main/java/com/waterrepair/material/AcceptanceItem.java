package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 验收依据明细：某管段上实际安装的具体批次、数量、扫码记录与当时有效的饮用水认证。
 * 即使批次事后被召回，本明细也不删除，用于回答“已安装材料的具体去向”。
 */
@Entity
@Table(name = "acceptance_item")
public class AcceptanceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long acceptanceId;

    @Column(nullable = false, length = 32)
    private String segmentCode;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, length = 64)
    private String batchNo;

    @Column(nullable = false)
    private Integer qty;

    /** 对应的安装扫码 id */
    private Long installScanId;

    @Column(length = 64)
    private String certNo;

    private LocalDate certExpiry;

    @Column(nullable = false, length = 16)
    private String certStatus;

    @Column(length = 128)
    private String locationText;

    public Long getId() {
        return id;
    }

    public Long getAcceptanceId() {
        return acceptanceId;
    }

    public void setAcceptanceId(Long acceptanceId) {
        this.acceptanceId = acceptanceId;
    }

    public String getSegmentCode() {
        return segmentCode;
    }

    public void setSegmentCode(String segmentCode) {
        this.segmentCode = segmentCode;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public Long getInstallScanId() {
        return installScanId;
    }

    public void setInstallScanId(Long installScanId) {
        this.installScanId = installScanId;
    }

    public String getCertNo() {
        return certNo;
    }

    public void setCertNo(String certNo) {
        this.certNo = certNo;
    }

    public LocalDate getCertExpiry() {
        return certExpiry;
    }

    public void setCertExpiry(LocalDate certExpiry) {
        this.certExpiry = certExpiry;
    }

    public String getCertStatus() {
        return certStatus;
    }

    public void setCertStatus(String certStatus) {
        this.certStatus = certStatus;
    }

    public String getLocationText() {
        return locationText;
    }

    public void setLocationText(String locationText) {
        this.locationText = locationText;
    }
}
