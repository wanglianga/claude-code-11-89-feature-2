package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialAllocationRepository extends JpaRepository<MaterialAllocation, Long> {
    List<MaterialAllocation> findByProvisionIdOrderByLineNoAscIdAsc(Long provisionId);

    List<MaterialAllocation> findByProvisionLineIdOrderByIdAsc(Long provisionLineId);

    List<MaterialAllocation> findByBatchNoOrderByIdAsc(String batchNo);
}
