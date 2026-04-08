package com.zzhy.yg_ai.service;

import java.time.LocalDate;

public interface SummaryContextCacheService {

    String getOrBuildEventExtractorContext(String reqno, LocalDate anchorDate);

    void evictPatientSummaryContexts(String reqno);
}
