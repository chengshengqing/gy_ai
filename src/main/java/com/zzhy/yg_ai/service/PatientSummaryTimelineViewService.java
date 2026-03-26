package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.dto.PatientTimelineViewData;

public interface PatientSummaryTimelineViewService {

    PatientTimelineViewData buildTimelineViewData(String reqno);
}
