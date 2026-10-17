package com.payverse.bill.repository;

import com.payverse.bill.entity.BillCategory;
import com.payverse.bill.entity.Biller;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillerRepository extends JpaRepository<Biller, UUID> {

    Optional<Biller> findByCode(String code);

    List<Biller> findByActiveTrueOrderByCategoryAscNameAsc();

    List<Biller> findByCategoryAndActiveTrueOrderByNameAsc(BillCategory category);
}
