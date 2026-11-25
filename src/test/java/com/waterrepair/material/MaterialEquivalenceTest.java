package com.waterrepair.material;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配料等价规则（不依赖 Spring）：
 * 口径、材质、连接方式必须完全一致，额定压力只允许不低于需求；
 * 外壳相似但压力等级不足的同系列件必须判为“不等价”，缺料时不得顶替。
 */
class MaterialEquivalenceTest {

    private MaterialProvisionLine line(int dn, String material, String connection, int pn) {
        MaterialProvisionLine l = new MaterialProvisionLine();
        l.setCategory("PIPE");
        l.setDn(dn);
        l.setMaterial(material);
        l.setConnectionType(connection);
        l.setPn(pn);
        l.setRequiredQty(1);
        return l;
    }

    private MaterialBatch batch(String no, int dn, String material, String connection, int pn) {
        return new MaterialBatch(no, "PIPE", dn, material, connection, pn, 10,
                "SUP", "SUP", "厂", "CERT", LocalDate.of(2028, 12, 31), "WH-MAIN", "A");
    }

    @Test
    void exactSpecIsIssuable() {
        MaterialProvisionLine need = line(100, "DUCTILE_IRON", "FLANGE", 16);
        assertThat(MaterialService.isEquivalent(need,
                batch("OK", 100, "DUCTILE_IRON", "FLANGE", 16))).isEqualTo("ISSUABLE");
        // 额定压力更高也可以
        assertThat(MaterialService.isEquivalent(need,
                batch("OK", 100, "DUCTILE_IRON", "FLANGE", 25))).isEqualTo("ISSUABLE");
    }

    @Test
    void lowerPressureRatingIsNotEquivalent() {
        MaterialProvisionLine need = line(100, "DUCTILE_IRON", "FLANGE", 16);
        List<String> mismatches = MaterialService.equivalenceMismatches(need,
                batch("PIPE-DN100-PN10-X", 100, "DUCTILE_IRON", "FLANGE", 10));
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0)).contains("PN10").contains("PN16");
        assertThat(MaterialService.isEquivalent(need,
                batch("PIPE-DN100-PN10-X", 100, "DUCTILE_IRON", "FLANGE", 10))
                .equals("ISSUABLE")).isFalse();
    }

    @Test
    void differentDiameterOrMaterialIsNotEquivalent() {
        MaterialProvisionLine need = line(100, "DUCTILE_IRON", "FLANGE", 16);
        assertThat(MaterialService.equivalenceMismatches(need,
                batch("DN80", 80, "DUCTILE_IRON", "FLANGE", 16))).anyMatch(s -> s.contains("DN80"));
        assertThat(MaterialService.equivalenceMismatches(need,
                batch("PE", 100, "PE", "FLANGE", 16))).anyMatch(s -> s.contains("PE"));
        assertThat(MaterialService.equivalenceMismatches(need,
                batch("GROOVED", 100, "DUCTILE_IRON", "GROOVED", 16)))
                .anyMatch(s -> s.contains("GROOVED"));
    }

    @Test
    void qualifiedFlagFollowsFreezeAndCert() {
        MaterialBatch b = batch("Q", 100, "DUCTILE_IRON", "FLANGE", 16);
        LocalDate today = LocalDate.of(2026, 11, 27);
        assertThat(b.qualified(today)).isTrue();
        assertThat(b.issuableQty(today)).isEqualTo(10);

        b.setStatus("FROZEN");
        assertThat(b.qualified(today)).isFalse();
        assertThat(b.issuableQty(today)).isZero();

        b.setStatus("QUALIFIED");
        b.setCertStatus("REVOKED");
        assertThat(b.qualified(today)).isFalse();
        assertThat(b.issuableQty(today)).isZero();
    }
}
