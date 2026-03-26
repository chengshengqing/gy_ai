package com.zzhy.yg_ai.controller;

import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.PatientTimelineViewData;
import com.zzhy.yg_ai.service.PatientSummaryTimelineViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patient-summary")
@RequiredArgsConstructor
public class PatientSummaryController {

    private final PatientSummaryTimelineViewService timelineViewService;

    @GetMapping("/timeline-view")
    public BaseResult<PatientTimelineViewData> timelineView(@RequestParam(required = false) String reqno) {
        return new BaseResult<>(timelineViewService.buildTimelineViewData(reqno));
    }
}
