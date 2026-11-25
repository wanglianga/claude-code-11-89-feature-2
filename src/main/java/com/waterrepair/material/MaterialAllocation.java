package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 配料单与具体批次之间的分配。BATCH 粒度在方案获批时创建并加锁；
 * CATEGORY 粒度在装车（派工单下达）时挑选批次后才创建。
 * 批次被冻结/召回时该分配置 BLOCKED 并释放未发部分的锁定数量。
 */
@Entity
@Table(name = "material_allocation")
public class MaterialAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long provisionId;

    @Column(nullable = false)
    private Long provisionLineId;

    @Column(nullable = false, length = 64)
    private String batchNo;

    @Column(nullable = false)
    private Integer allocatedQty;

    @Column(nullable = false)
    private Integer issuedQty = 0;

    @Column(nullable = false)
    private Integer returnedQty = 0;

    @Column(nullable = false)
    private Integer installedQty = 0;

    /** ACTIVE / BLOCKED / RELEASED */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(length = 256)
    private String releaseReason;

    @Column(nullable = false, length = 1)
    private Integer lineNo;

    public Long getId() {
        return id;
    }

    public Long getProvisionId() {
        return provisionId;
    }

    public void setProvisionId(Long provisionId) {
        this.provisionId = provisionId;
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

    public Integer getAllocatedQty() {
        return allocatedQty;
    }

    public void setAllocatedQty(Integer allocatedQty) {
        this.allocatedQty = allocatedQty;
    }

    public Integer getIssuedQty() {
        return issuedQty;
    }

    public void setIssuedQty(Integer issuedQty) {
        this.issuedQty = issuedQty;
    }

    public Integer getReturnedQty() {
        return returnedQty;
    }

    public void setReturnedQty(Integer returnedQty) {
        this.returnedQty = returnedQty;
    }

    public Integer getInstalledQty() {
        return installedQty;
    }

    public void setInstalledQty(Integer installedQty) {
        this.installedQty = installedQty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    public void setReleaseReason(String releaseReason) {
        this.releaseReason = releaseReason;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }
}
