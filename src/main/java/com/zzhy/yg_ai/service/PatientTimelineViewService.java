package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.dto.PatientTimelineViewData;

public interface PatientTimelineViewService {

    PatientTimelineViewData buildTimelineViewData(String reqno, int pageNo, int pageSize);
}
