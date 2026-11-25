package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkAcceptanceRepository extends JpaRepository<WorkAcceptance, Long> {
    List<WorkAcceptance> findByIncidentIdOrderByIdAsc(Long incidentId);

    Optional<WorkAcceptance> findFirstByIncidentIdAndStatusOrderByIdDesc(Long incidentId, String status);
}
