package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepairPlanRepository extends JpaRepository<RepairPlan, Long> {
    List<RepairPlan> findByIncidentIdOrderByVersionNoAscIdAsc(Long incidentId);

    Optional<RepairPlan> findFirstByIncidentIdOrderByVersionNoDescIdDesc(Long incidentId);

    Optional<RepairPlan> findFirstByIncidentIdAndStatusOrderByVersionNoDescIdDesc(Long incidentId, String status);
}
