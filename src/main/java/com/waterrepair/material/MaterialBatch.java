package com.waterrepair.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 仓库中的一个材料批次（合格批次库存）。
 *
 * <p>quantityOnHand 是仓库实物可用数量（退回后会回补），quantityLocked 是被“具体批次锁定”
 * 的配料单占用的数量（仍在库但不可动用）。issuableQty = 合格且未冻结/未召回时的
 * (onHand - locked)。冻结或召回后一律 0。</p>
 */
@Entity
@Table(name = "material_batch")
public class MaterialBatch {

    @Id
    @Column(length = 64)
    private String batchNo;

    /** PIPE / SEAL / COUPLING / VALVE_BODY 等 */
    @Column(nullable = false, length = 32)
    private String category;

    /** 公称口径 DN */
    @Column(nullable = false)
    private Integer dn;

    /** 材质，如 DUCTILE_IRON / EPDM / STAINLESS */
    @Column(nullable = false, length = 32)
    private String material;

    /** 连接方式：FLANGE / GROOVED / COMPRESSION / WELD */
    @Column(nullable = false, length = 24)
    private String connectionType;

    /** 额定压力 PN */
    @Column(nullable = false)
    private Integer pn;

    @Column(nullable = false)
    private Integer quantityTotal;

    @Column(nullable = false)
    private Integer quantityOnHand;

    @Column(nullable = false)
    private Integer quantityLocked = 0;

    @Column(nullable = false, length = 32)
    private String supplierCode;

    @Column(length = 128)
    private String supplierName;

    @Column(length = 128)
    private String manufacturer;

    /** 饮用水卫生认证编号 */
    @Column(length = 64)
    private String certNo;

    /** VALID / REVOKED */
    @Column(nullable = false, length = 16)
    private String certStatus = "VALID";

    private LocalDate certExpiry;

    @Column(nullable = false, length = 32)
    private String warehouseCode = "WH-MAIN";

    @Column(length = 64)
    private String shelfLocation;

    /** QUALIFIED / FROZEN / RECALLED */
    @Column(nullable = false, length = 16)
    private String status = "QUALIFIED";

    private Long recallId;

    @Column(length = 256)
    private String statusReason;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Version
    private Long version;

    public MaterialBatch() {
    }

    public MaterialBatch(String batchNo, String category, Integer dn, String material,
                         String connectionType, Integer pn, Integer quantityTotal,
                         String supplierCode, String supplierName, String manufacturer,
                         String certNo, LocalDate certExpiry, String warehouseCode,
                         String shelfLocation) {
        this.batchNo = batchNo;
        this.category = category;
        this.dn = dn;
        this.material = material;
        this.connectionType = connectionType;
        this.pn = pn;
        this.quantityTotal = quantityTotal;
        this.quantityOnHand = quantityTotal;
        this.supplierCode = supplierCode;
        this.supplierName = supplierName;
        this.manufacturer = manufacturer;
        this.certNo = certNo;
        this.certExpiry = certExpiry;
        this.warehouseCode = warehouseCode;
        this.shelfLocation = shelfLocation;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public String getCategory() {
        return category;
    }

    public Integer getDn() {
        return dn;
    }

    public String getMaterial() {
        return material;
    }

    public String getConnectionType() {
        return connectionType;
    }

    public Integer getPn() {
        return pn;
    }

    public Integer getQuantityTotal() {
        return quantityTotal;
    }

    public Integer getQuantityOnHand() {
        return quantityOnHand;
    }

    public void setQuantityOnHand(Integer quantityOnHand) {
        this.quantityOnHand = quantityOnHand;
        this.updatedAt = OffsetDateTime.now();
    }

    public Integer getQuantityLocked() {
        return quantityLocked;
    }

    public void setQuantityLocked(Integer quantityLocked) {
        this.quantityLocked = quantityLocked;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getCertNo() {
        return certNo;
    }

    public String getCertStatus() {
        return certStatus;
    }

    public void setCertStatus(String certStatus) {
        this.certStatus = certStatus;
    }

    public LocalDate getCertExpiry() {
        return certExpiry;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public String getShelfLocation() {
        return shelfLocation;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getRecallId() {
        return recallId;
    }

    public void setRecallId(Long recallId) {
        this.recallId = recallId;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    /** 证书是否在有效期内（到期日不早于今天）。 */
    public boolean certEffective(LocalDate today) {
        return "VALID".equals(certStatus) && (certExpiry == null || !certExpiry.isBefore(today));
    }

    public boolean qualified(LocalDate today) {
        return "QUALIFIED".equals(status) && certEffective(today);
    }

    public int issuableQty(LocalDate today) {
        if (!qualified(today)) {
            return 0;
        }
        return Math.max(0, quantityOnHand - quantityLocked);
    }
}
