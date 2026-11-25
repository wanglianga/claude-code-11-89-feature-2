package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 维修方案的一行材料需求（口径/材质/连接方式/额定压力/数量/安装管段）。 */
@Entity
@Table(name = "repair_plan_line")
public class RepairPlanLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false)
    private Integer lineNo;

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
    private Integer quantity;

    @Column(nullable = false, length = 32)
    private String segmentCode;

    @Column(length = 128)
    private String locationText;

    public Long getId() {
        return id;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
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

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
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
}
