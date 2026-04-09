package com.zzhy.yg_ai.domain.normalize.prompt;

import com.zzhy.yg_ai.ai.prompt.FormatAgentPrompt;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NormalizePromptCatalog {

    private static final Set<String> ALLOWED_STATUS = Set.of(
            "active", "acute_exacerbation", "worsening", "improving", "chronic", "stable", "clarified", "unclear"
    );
    private static final Set<String> ALLOWED_CERTAINTY = Set.of(
            "confirmed", "suspected", "possible", "workup_needed", "risk_only"
    );
    private static final Set<String> ALLOWED_PRIORITY = Set.of("high", "medium", "low");
    private static final Set<String> ALLOWED_PROBLEM_TYPE = Set.of(
            "disease", "complication", "chronic", "risk_state", "differential"
    );
    private static final Set<String> ALLOWED_BASELINE_CERTAINTY = Set.of("confirmed", "suspected", "workup_needed");
    private static final Set<String> ALLOWED_BASELINE_TIME_STATUS = Set.of("active", "acute_exacerbation", "chronic", "unclear");
    private static final Set<String> ALLOWED_SEVERITY = Set.of("mild", "moderate", "severe", "critical", "unclear");
    private static final Set<String> ALLOWED_DIFFERENTIAL_CERTAINTY = Set.of("suspected", "workup_needed");
    private static final Set<String> ALLOWED_RISK_ALERT_CERTAINTY = Set.of("risk_only", "suspected");
    private static final Set<String> ALLOWED_CONSULT_CERTAINTY = Set.of("confirmed", "suspected", "workup_needed", "risk_only");

    public NormalizePromptDefinition selectIllnessPrompt(String itemname) {
        return switch (IllnessRecordType.resolve(itemname)) {
            case FIRST_COURSE, ADMISSION -> new NormalizePromptDefinition(
                    NormalizePromptType.FIRST_ILLNESS_COURSE_NEW,
                    FormatAgentPrompt.FIRST_ILLNESS_COURSE_PROMPT_NEW,
                    ""
            );
            case CONSULTATION -> new NormalizePromptDefinition(
                    NormalizePromptType.CONSULTATION_ILLNESS_COURSE_NEW,
                    FormatAgentPrompt.CONSULTATION_ILLNESS_COURSE_PROMPT_NEW,
                    ""
            );
            case SURGERY -> new NormalizePromptDefinition(
                    NormalizePromptType.SURGERY_ILLNESS_COURSE_NEW,
                    FormatAgentPrompt.SURGERY_ILLNESS_COURSE_PROMPT_NEW,
                    ""
            );
            case DAILY -> new NormalizePromptDefinition(
                    NormalizePromptType.ILLNESS_COURSE_NEW,
                    FormatAgentPrompt.ILLNESS_COURSE_PROMPT_NEW,
                    ""
            );
        };
    }

    public NormalizePromptDefinition dailyFusionPrompt() {
        return new NormalizePromptDefinition(
                NormalizePromptType.DAILY_FUSION,
                FormatAgentPrompt.DAILY_FUSION_SYSTEM_PROMPT,
                FormatAgentPrompt.DAILY_FUSION_USER_PROMPT
        );
    }

    public NormalizePromptValidationSpec validationSpecFor(NormalizePromptType promptType) {
        return switch (promptType) {
            case FIRST_ILLNESS_COURSE_NEW -> new NormalizePromptValidationSpec(List.of(
                    new NormalizeEnumFieldRule("core_problems[].certainty", ALLOWED_BASELINE_CERTAINTY, true),
                    new NormalizeEnumFieldRule("core_problems[].time_status", ALLOWED_BASELINE_TIME_STATUS, true),
                    new NormalizeEnumFieldRule("core_problems[].severity", ALLOWED_SEVERITY, true),
                    new NormalizeEnumFieldRule("differential_diagnosis[].certainty", ALLOWED_DIFFERENTIAL_CERTAINTY, true)
            ));
            case ILLNESS_COURSE_NEW -> new NormalizePromptValidationSpec(List.of(
                    new NormalizeEnumFieldRule("risk_alerts[].certainty", ALLOWED_RISK_ALERT_CERTAINTY, true)
            ));
            case CONSULTATION_ILLNESS_COURSE_NEW -> new NormalizePromptValidationSpec(List.of(
                    new NormalizeEnumFieldRule("consult_core_judgment[].certainty", ALLOWED_CONSULT_CERTAINTY, true),
                    new NormalizeEnumFieldRule("risk_alerts[].certainty", ALLOWED_RISK_ALERT_CERTAINTY, true)
            ));
            case SURGERY_ILLNESS_COURSE_NEW -> new NormalizePromptValidationSpec(List.of());
            case DAILY_FUSION -> new NormalizePromptValidationSpec(List.of(
                    new NormalizeEnumFieldRule("problem_list[].problem_type", ALLOWED_PROBLEM_TYPE, true),
                    new NormalizeEnumFieldRule("problem_list[].priority", ALLOWED_PRIORITY, true),
                    new NormalizeEnumFieldRule("problem_list[].status", ALLOWED_STATUS, true),
                    new NormalizeEnumFieldRule("problem_list[].certainty", ALLOWED_CERTAINTY, true)
            ));
        };
    }
}
