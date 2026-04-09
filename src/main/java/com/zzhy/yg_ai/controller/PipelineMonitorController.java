package com.zzhy.yg_ai.controller;

import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.monitor.PipelineMonitorDashboardView;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pipeline-monitor")
@RequiredArgsConstructor
public class PipelineMonitorController {

    private final PipelineMonitorDashboardService pipelineMonitorDashboardService;

    @GetMapping("/dashboard")
    public BaseResult<PipelineMonitorDashboardView> dashboard() {
        return new BaseResult<>(pipelineMonitorDashboardService.getDashboard());
    }
}
