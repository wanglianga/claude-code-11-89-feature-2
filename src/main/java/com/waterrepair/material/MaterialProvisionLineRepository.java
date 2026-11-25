package com.waterrepair.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialProvisionLineRepository extends JpaRepository<MaterialProvisionLine, Long> {
    List<MaterialProvisionLine> findByProvisionIdOrderByLineNoAscIdAsc(Long provisionId);
}
