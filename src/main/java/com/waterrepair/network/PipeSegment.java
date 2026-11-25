package com.waterrepair.network;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 管段：连接两个节点。normalPressureMin/Max 为该支管复压核验的正常压力区间（MPa）。
 */
@Entity
@Table(name = "pipe_segment")
public class PipeSegment {

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String fromNodeCode;

    @Column(nullable = false, length = 32)
    private String toNodeCode;

    @Column(nullable = false)
    private Double normalPressureMinMpa;

    @Column(nullable = false)
    private Double normalPressureMaxMpa;

    /** 采样时刻经校时偏差修正后，与复压回执时刻允许的最大偏差（秒） */
    @Column(nullable = false)
    private Integer pressureTimeToleranceSeconds;

    /** 管段的设计口径 DN；维修方案计算的管径以此为准。 */
    @Column(nullable = false)
    private Integer designDn = 100;

    /** 管段材质（如 DUCTILE_IRON / STEEL / PE）。 */
    @Column(nullable = false, length = 32)
    private String material = "DUCTILE_IRON";

    /** 既有管段连接方式（FLANGE / GROOVED / COMPRESSION / WELD）。 */
    @Column(nullable = false, length = 24)
    private String connectionType = "FLANGE";

    /** 管段额定压力 PN。 */
    @Column(nullable = false)
    private Integer designPn = 16;

    public PipeSegment() {
    }

    public PipeSegment(String code, String name, String fromNodeCode, String toNodeCode,
                       Double normalPressureMinMpa, Double normalPressureMaxMpa,
                       Integer pressureTimeToleranceSeconds) {
        this.code = code;
        this.name = name;
        this.fromNodeCode = fromNodeCode;
        this.toNodeCode = toNodeCode;
        this.normalPressureMinMpa = normalPressureMinMpa;
        this.normalPressureMaxMpa = normalPressureMaxMpa;
        this.pressureTimeToleranceSeconds = pressureTimeToleranceSeconds;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getFromNodeCode() {
        return fromNodeCode;
    }

    public String getToNodeCode() {
        return toNodeCode;
    }

    public Double getNormalPressureMinMpa() {
        return normalPressureMinMpa;
    }

    public Double getNormalPressureMaxMpa() {
        return normalPressureMaxMpa;
    }

    public Integer getPressureTimeToleranceSeconds() {
        return pressureTimeToleranceSeconds;
    }

    public Integer getDesignDn() {
        return designDn;
    }

    public String getMaterial() {
        return material;
    }

    public String getConnectionType() {
        return connectionType;
    }

    public Integer getDesignPn() {
        return designPn;
    }

    public void setDesignSpec(Integer designDn, String material, String connectionType, Integer designPn) {
        this.designDn = designDn == null ? 100 : designDn;
        this.material = material == null ? "DUCTILE_IRON" : material;
        this.connectionType = connectionType == null ? "FLANGE" : connectionType;
        this.designPn = designPn == null ? 16 : designPn;
    }
}
