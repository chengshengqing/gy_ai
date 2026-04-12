package com.zzhy.yg_ai.controller;

import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.demo.InfectionPreReviewDemoSnapshotResult;
import com.zzhy.yg_ai.service.impl.InfectionPreReviewDemoSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo/infection-pre-review")
@RequiredArgsConstructor
public class InfectionPreReviewDemoController {

    private final InfectionPreReviewDemoSnapshotService snapshotService;

    @GetMapping("/snapshot/generate")
    public BaseResult<InfectionPreReviewDemoSnapshotResult> generate() {
        return new BaseResult<>(snapshotService.generateSnapshots());
    }
}
