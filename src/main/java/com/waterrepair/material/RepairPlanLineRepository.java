package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepairPlanLineRepository extends JpaRepository<RepairPlanLine, Long> {
    List<RepairPlanLine> findByPlanIdOrderByLineNoAscIdAsc(Long planId);
}
