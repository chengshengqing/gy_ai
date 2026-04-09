package com.zzhy.yg_ai.service;

import java.time.LocalDate;

public interface SummaryContextCacheService {

    String getOrBuildEventExtractorContext(String reqno, LocalDate anchorDate);

    void refreshEventExtractorContextDay(String reqno, LocalDate dataDate, String eventJson);

    void evictPatientSummaryContexts(String reqno);
}
