package com.payverse.recon.repository;

import com.payverse.recon.entity.ReconJob;
import com.payverse.recon.entity.ReconRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconRunRepository extends JpaRepository<ReconRun, UUID> {

    Page<ReconRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    Page<ReconRun> findByJobOrderByStartedAtDesc(ReconJob job, Pageable pageable);
}
