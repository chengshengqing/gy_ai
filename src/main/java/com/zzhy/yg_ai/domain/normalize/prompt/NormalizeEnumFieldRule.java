package com.zzhy.yg_ai.domain.normalize.prompt;

import java.util.Set;

public record NormalizeEnumFieldRule(
        String jsonPath,
        Set<String> allowedValues,
        boolean required
) {
}
