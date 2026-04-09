package com.zzhy.yg_ai.pipeline.model;

import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import java.util.List;

public record NormalizeResult(
        List<Long> taskIds,
        String reqno,
        int totalRows,
        int successCount,
        int failedCount,
        String lastErrorMessage,
        InfectionJobStage failedStage
) {
}
