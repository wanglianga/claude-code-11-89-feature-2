package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AcceptanceItemRepository extends JpaRepository<AcceptanceItem, Long> {
    List<AcceptanceItem> findByAcceptanceIdOrderByIdAsc(Long acceptanceId);

    List<AcceptanceItem> findByBatchNoOrderByIdAsc(String batchNo);
}
