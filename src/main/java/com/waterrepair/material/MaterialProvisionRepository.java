package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialProvisionRepository extends JpaRepository<MaterialProvision, Long> {
    List<MaterialProvision> findByIncidentIdOrderByIdAsc(Long incidentId);

    Optional<MaterialProvision> findFirstByIncidentIdOrderByIdDesc(Long incidentId);
}
