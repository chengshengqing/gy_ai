package com.zzhy.yg_ai.domain.normalize.prompt;

import java.util.List;

public record NormalizePromptValidationSpec(
        List<NormalizeEnumFieldRule> rules
) {
}
