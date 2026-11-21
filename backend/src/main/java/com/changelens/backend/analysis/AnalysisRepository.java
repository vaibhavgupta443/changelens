package com.changelens.backend.analysis;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRepository extends JpaRepository<AnalysisRun, String> {

    List<AnalysisRun> findByStatusIn(List<AnalysisStatus> statuses);
}
