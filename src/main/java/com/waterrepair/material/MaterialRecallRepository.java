package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialRecallRepository extends JpaRepository<MaterialRecall, Long> {
    List<MaterialRecall> findByBatchNoOrderByIdAsc(String batchNo);

    List<MaterialRecall> findAllByOrderByIdAsc();
}
