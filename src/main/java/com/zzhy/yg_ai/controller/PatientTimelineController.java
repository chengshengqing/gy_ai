package com.zzhy.yg_ai.controller;

import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.PatientTimelineViewData;
import com.zzhy.yg_ai.service.PatientTimelineViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patient-summary")
@RequiredArgsConstructor
public class PatientTimelineController {

    private final PatientTimelineViewService timelineViewService;

    @GetMapping("/timeline-view")
    public BaseResult<PatientTimelineViewData> timelineView(@RequestParam(required = false) String reqno,
                                                            @RequestParam(defaultValue = "1") int pageNo,
                                                            @RequestParam(defaultValue = "10") int pageSize) {
        return new BaseResult<>(timelineViewService.buildTimelineViewData(reqno, pageNo, pageSize));
    }
}
