package com.zzhy.yg_ai.domain.format;

import com.zzhy.yg_ai.ai.agent.AgentUtils;
import com.zzhy.yg_ai.ai.gateway.AiGateway;
import com.zzhy.yg_ai.ai.prompt.FormatAgentPrompt;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FormatSectionFormatter {

    private static final String NODE_TYPE = "FORMAT_SECTION";

    private final AiGateway aiGateway;

    public String formatIllnessSection(String illnessJson) {
        return AgentUtils.formatSectionWithSplit(
                "pat_illnessCourse",
                illnessJson,
                chunk -> callWithPrompt(FormatAgentPrompt.ILLNESS_COURSE_PROMPT, chunk)
        );
    }

    public String formatOtherSection(String otherJson) {
        return AgentUtils.formatSectionWithSplit(
                "otherInfo",
                otherJson,
                chunk -> callWithPrompt(FormatAgentPrompt.OTHER_INFO_PROMPT, chunk)
        );
    }

    public String callWithPrompt(String promptTemplate, String inputJson) {
        String resolvedPrompt = resolvePromptTemplate(promptTemplate, inputJson);
        return aiGateway.callSystem(PipelineStage.NORMALIZE, NODE_TYPE, resolvedPrompt, inputJson);
    }

    private String resolvePromptTemplate(String promptTemplate, String inputJson) {
        if (IllnessRecordType.FIRST_COURSE.matches(inputJson)) {
            return FormatAgentPrompt.FIRST_ILLNESS_COURSE_PROMPT;
        }
        if (IllnessRecordType.CONSULTATION.matches(inputJson)) {
            return FormatAgentPrompt.CONSULTATION_ILLNESS_COURSE_PROMPT;
        }
        if (IllnessRecordType.SURGERY.matches(inputJson)) {
            return FormatAgentPrompt.SURGERY_ILLNESS_COURSE_PROMPT;
        }
        return promptTemplate;
    }
}
