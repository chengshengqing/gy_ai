package com.zzhy.yg_ai.domain.normalize.assemble;

import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptDefinition;

public record DailyFusionPlan(
        boolean enabled,
        String skipReason,
        NormalizePromptDefinition promptDefinition,
        String inputJson
) {
}
