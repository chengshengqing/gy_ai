package com.zzhy.yg_ai.ai.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import org.junit.jupiter.api.Test;

class WarningPromptCatalogTest {

    @Test
    void clinicalTextPromptRequiresNullSourceSectionAndTextOnlyEvidence() {
        String prompt = WarningPromptCatalog.buildEventExtractorPrompt(EvidenceBlockType.CLINICAL_TEXT);

        assertTrue(prompt.contains("source_section 必须输出 JSON null"));
        assertTrue(prompt.contains("不得把 structuredContext 中的原句、检验值、影像结论、医嘱内容直接作为 source_text"));
        assertTrue(prompt.contains("如果一个事件只能由 structuredContext 或 recentChangeContext 支撑"));
    }
}
