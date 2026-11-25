package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialScanRepository extends JpaRepository<MaterialScan, Long> {
    List<MaterialScan> findByProvisionIdOrderByIdAsc(Long provisionId);

    List<MaterialScan> findByIncidentIdOrderByIdAsc(Long incidentId);

    List<MaterialScan> findByBatchNoOrderByIdAsc(String batchNo);

    List<MaterialScan> findByProvisionLineIdOrderByIdAsc(Long provisionLineId);

    List<MaterialScan> findByKindAndBatchNoOrderByIdAsc(String kind, String batchNo);
}
