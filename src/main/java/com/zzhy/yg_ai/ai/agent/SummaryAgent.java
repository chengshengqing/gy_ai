package com.zzhy.yg_ai.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.prompt.FormatAgentPrompt;
import com.zzhy.yg_ai.ai.prompt.SummaryAgentPrompt;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.model.PatientContext;
import com.zzhy.yg_ai.domain.model.PatientSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class SummaryAgent extends AbstractAgent {

    private final ReactAgent reactAgent;
    private final ObjectMapper objectMapper;
    private final AgentUtils agentUtils;

    public SummaryAgent(@Qualifier("summaryReactAgent") ReactAgent reactAgent,
                        ObjectMapper objectMapper) {
        this.reactAgent = reactAgent;
        this.objectMapper = objectMapper;
        this.agentUtils = new AgentUtils(objectMapper);
    }


    public String extractDailyEvents(PatientRawDataEntity rawDataEntity, PatientSummaryEntity latestSummary) {
        String input = StringUtils.hasText(rawDataEntity.getDataJson()) ? rawDataEntity.getDataJson() : "{}";
        Map<String, String> splitInput = agentUtils.splitInput(input);

        return null;
    }

    public ReactAgent getReactAgent() {
        return reactAgent;
    }

    private String normalizeJson(String output) {
        if (!StringUtils.hasText(output)) {
            return "{}";
        }
        String trimmed = output.trim();
        try {
            objectMapper.readTree(trimmed);
            return trimmed;
        } catch (Exception ignored) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String candidate = trimmed.substring(start, end + 1);
                try {
                    objectMapper.readTree(candidate);
                    return candidate;
                } catch (Exception e) {
                    log.warn("SummaryAgent返回非JSON，保留原文");
                }
            }
            return trimmed;
        }
    }

    private String callWithPrompt(String promptTemplate, String inputJson) {
        String systemPrompt = promptTemplate.replace("{input_json}", inputJson);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(inputJson)
        ));
        ChatResponse response = super.callModelByPrompt(prompt);
        return normalizeJson(response.getResult().getOutput().getText());
    }

}
