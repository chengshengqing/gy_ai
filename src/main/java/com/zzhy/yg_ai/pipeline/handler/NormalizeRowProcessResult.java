package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import org.springframework.util.StringUtils;

record NormalizeRowProcessResult(InfectionJobStage failedStage, String errorMessage) {

    static NormalizeRowProcessResult success() {
        return new NormalizeRowProcessResult(null, null);
    }

    static NormalizeRowProcessResult failed(InfectionJobStage failedStage, String errorMessage) {
        return new NormalizeRowProcessResult(failedStage, errorMessage);
    }

    boolean isSuccess() {
        return failedStage == null && !StringUtils.hasText(errorMessage);
    }
}
