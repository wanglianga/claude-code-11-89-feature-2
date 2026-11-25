package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 配料单的一行需求快照（从维修方案行复制，保留当时的参数，便于追责与对比）。 */
@Entity
@Table(name = "material_provision_line")
public class MaterialProvisionLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long provisionId;

    @Column(nullable = false)
    private Integer lineNo;

    private Long planLineId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false)
    private Integer dn;

    @Column(nullable = false, length = 32)
    private String material;

    @Column(nullable = false, length = 24)
    private String connectionType;

    @Column(nullable = false)
    private Integer pn;

    @Column(nullable = false)
    private Integer requiredQty;

    @Column(nullable = false, length = 32)
    private String segmentCode;

    @Column(length = 128)
    private String locationText;

    /** OPEN / FULFILLED / RELEASED */
    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    public Long getId() {
        return id;
    }

    public Long getProvisionId() {
        return provisionId;
    }

    public void setProvisionId(Long provisionId) {
        this.provisionId = provisionId;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public Long getPlanLineId() {
        return planLineId;
    }

    public void setPlanLineId(Long planLineId) {
        this.planLineId = planLineId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getDn() {
        return dn;
    }

    public void setDn(Integer dn) {
        this.dn = dn;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public String getConnectionType() {
        return connectionType;
    }

    public void setConnectionType(String connectionType) {
        this.connectionType = connectionType;
    }

    public Integer getPn() {
        return pn;
    }

    public void setPn(Integer pn) {
        this.pn = pn;
    }

    public Integer getRequiredQty() {
        return requiredQty;
    }

    public void setRequiredQty(Integer requiredQty) {
        this.requiredQty = requiredQty;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
