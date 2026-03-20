package com.zzhy.yg_ai.controller;

import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.PatientRawDataTimelineGroup;
import com.zzhy.yg_ai.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 患者原始病程数据（演示页接口）。
 */
@RestController
@RequestMapping("/api/patient-raw-data")
@RequiredArgsConstructor
public class PatientRawDataController {

    private final PatientService patientService;

    @GetMapping("/timeline-demo")
    public BaseResult<List<PatientRawDataTimelineGroup>> timelineDemo(
            @RequestParam(required = false) String reqno) {
        return new BaseResult<>(patientService.listRawDataGroupedByReqnoForDemo(reqno));
    }
}
