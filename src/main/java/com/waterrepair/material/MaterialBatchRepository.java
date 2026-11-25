package com.waterrepair.material;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MaterialBatchRepository extends JpaRepository<MaterialBatch, String> {

    Optional<MaterialBatch> findFirstByBatchNo(String batchNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MaterialBatch b where b.batchNo = :batchNo")
    Optional<MaterialBatch> findForUpdate(@Param("batchNo") String batchNo);
}
