package com.zzhy.yg_ai.domain.format;

import com.zzhy.yg_ai.ai.agent.AgentUtils;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import com.zzhy.yg_ai.domain.model.PatientContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class FormatContextComposer {

    private static final String FORMAT_AGENT_SOURCE = "format-agent";
    private static final String FORMAT_FAILED_JSON = "{\"code\":\"500\",\"message\":\"格式化失败，llm未处理\"}";

    private final FormatSectionFormatter formatSectionFormatter;
    private final FinalMergePromptBuilder finalMergePromptBuilder;

    public PatientContext compose(String rawDataJson, String inhosdateRawJson) {
        String input = StringUtils.hasText(rawDataJson) ? rawDataJson : "{}";
        String inhosdateJson = StringUtils.hasText(inhosdateRawJson) ? inhosdateRawJson : "{}";
        try {
            FormatInput inputSections = prepareInput(input, inhosdateJson);
            String illnessPart = formatSectionFormatter.formatIllnessSection(inputSections.illnessJson());
            String otherPart = formatSectionFormatter.formatOtherSection(inputSections.otherJson());
            String finalInput = AgentUtils.toJson(AgentUtils.prepareMergeInput(illnessPart, otherPart));
            String finalPrompt = finalMergePromptBuilder.build(inputSections.hasFirstIllnessCourse());
            String finalOutput = formatSectionFormatter.callWithPrompt(finalPrompt, finalInput);
            return buildContext(AgentUtils.normalizeToJson(finalOutput));
        } catch (Exception e) {
            log.error("format-agent 处理失败，回退错误消息", e);
            return buildContext(FORMAT_FAILED_JSON);
        }
    }

    private FormatInput prepareInput(String rawDataJson, String inhosdateRawJson) {
        Map<String, String> splitInput = AgentUtils.splitInput(rawDataJson);
        Map<String, String> splitInhosdate = AgentUtils.splitInput(inhosdateRawJson);
        String illnessJson = defaultJson(splitInput.get("illnessJson"));
        String otherJson = defaultJson(splitInput.get("otherJson"));
        String inhosdateIllnessJson = defaultJson(splitInhosdate.get("illnessJson"));
        String deduplicatedIllnessJson = AgentUtils.removeDuplicateSentences(inhosdateIllnessJson, illnessJson);
        return new FormatInput(
                deduplicatedIllnessJson,
                otherJson,
                IllnessRecordType.FIRST_COURSE.matches(deduplicatedIllnessJson)
        );
    }

    private PatientContext buildContext(String contextJson) {
        PatientContext context = new PatientContext();
        context.setSource(FORMAT_AGENT_SOURCE);
        context.setCreatedAt(DateTimeUtils.now());
        context.setContextJson(contextJson);
        return context;
    }

    private String defaultJson(String value) {
        return StringUtils.hasText(value) ? value : "{}";
    }

    private record FormatInput(String illnessJson, String otherJson, boolean hasFirstIllnessCourse) {
    }
}
