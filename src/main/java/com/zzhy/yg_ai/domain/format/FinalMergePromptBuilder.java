package com.zzhy.yg_ai.domain.format;

import com.zzhy.yg_ai.ai.prompt.FormatAgentPrompt;
import org.springframework.stereotype.Component;

@Component
public class FinalMergePromptBuilder {

    public String build(boolean hasFirstIllnessCourse) {
        String conditionOverview = "";
        String conditionOverviewDesc = "";
        if (hasFirstIllnessCourse) {
            conditionOverview = "condition_overview: [],";
            conditionOverviewDesc = """
            condition_overview
            患者主要诊断""";
        }
        return FormatAgentPrompt.FINAL_MERGE_PROMPT
                .replace("{conditionOverview}", conditionOverview)
                .replace("{conditionOverviewDesc}", conditionOverviewDesc);
    }
}
