package com.zzhy.yg_ai.domain.model;

import lombok.Builder;

@Builder
public record InfectionJudgePrecompute(
        Boolean newOnsetFlag,
        String after48hFlag,
        Boolean procedureRelatedFlag,
        Boolean deviceRelatedFlag,
        String precomputeReasonJson
) {
}
