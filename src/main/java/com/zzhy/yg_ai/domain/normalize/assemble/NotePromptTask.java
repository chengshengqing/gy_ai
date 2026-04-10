package com.zzhy.yg_ai.domain.normalize.assemble;

import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptDefinition;

public record NotePromptTask(
        String noteType,
        String noteTime,
        int sortPriority,
        NormalizePromptDefinition promptDefinition,
        String inputJson
) {
}
