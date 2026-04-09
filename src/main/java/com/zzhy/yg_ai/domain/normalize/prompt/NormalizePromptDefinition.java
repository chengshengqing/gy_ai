package com.zzhy.yg_ai.domain.normalize.prompt;

import org.springframework.util.StringUtils;

public record NormalizePromptDefinition(
        NormalizePromptType type,
        String systemPrompt,
        String userPrompt
) {

    public boolean hasUserPrompt() {
        return StringUtils.hasText(userPrompt);
    }
}
