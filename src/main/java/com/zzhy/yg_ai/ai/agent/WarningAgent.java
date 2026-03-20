package com.zzhy.yg_ai.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.prompt.WarningAgentPrompt;
import com.zzhy.yg_ai.domain.model.InfectionAlert;
import com.zzhy.yg_ai.domain.model.InfectionType;
import com.zzhy.yg_ai.domain.model.PatientSummary;
import com.zzhy.yg_ai.domain.model.RiskLevel;
import java.time.LocalDateTime;
import java.util.List;
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
public class WarningAgent extends AbstractAgent {

    private final ReactAgent reactAgent;
    private final ObjectMapper objectMapper;

    public WarningAgent(@Qualifier("warningReactAgent") ReactAgent reactAgent,
                        ObjectMapper objectMapper) {
        this.reactAgent = reactAgent;
        this.objectMapper = objectMapper;
    }

    public InfectionAlert createAlert(PatientSummary summary) {
        String input = summary == null || !StringUtils.hasText(summary.getSummaryJson()) ? "{}" : summary.getSummaryJson();
        return evaluateTimeline(summary == null ? null : summary.getReqno(), input);
    }

    public InfectionAlert evaluateTimeline(String reqno, String timelineJson) {
        String input = StringUtils.hasText(timelineJson) ? timelineJson : "{}";
        String systemPrompt = WarningAgentPrompt.EVALUATE_PROMPT.replace("{input_json}", input);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(input)
        ));
        ChatResponse response = super.callModelByPrompt(prompt);
        String output = normalizeJson(response.getResult().getOutput().getText());

        InfectionAlert alert = new InfectionAlert();
        alert.setReqno(reqno);
        alert.setAlertTime(LocalDateTime.now());
        alert.setStatus("NEW");
        try {
            JsonNode node = objectMapper.readTree(output);
            String risk = node.path("risk_level").asText("low");
            String type = node.path("suspected_infection_type").asText("unknown");
            JsonNode evidenceNode = node.path("evidence");
            alert.setRiskLevel(parseRiskLevel(risk));
            alert.setInfectionType(parseInfectionType(type));
            alert.setEvidence(evidenceNode.toString());
            alert.setRuleCode("WARNING_AGENT");
        } catch (Exception e) {
            log.warn("WarningAgent结果解析失败", e);
            alert.setRiskLevel(RiskLevel.LOW);
            alert.setInfectionType(InfectionType.OTHER);
            alert.setEvidence(output);
            alert.setRuleCode("WARNING_AGENT_PARSE_FALLBACK");
        }
        return alert;
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
                    log.warn("WarningAgent返回非JSON，保留原文");
                }
            }
            return trimmed;
        }
    }

    private RiskLevel parseRiskLevel(String risk) {
        if (!StringUtils.hasText(risk)) {
            return RiskLevel.LOW;
        }
        String normalized = risk.trim().toUpperCase();
        return switch (normalized) {
            case "HIGH" -> RiskLevel.HIGH;
            case "MEDIUM" -> RiskLevel.MEDIUM;
            case "CRITICAL" -> RiskLevel.CRITICAL;
            default -> RiskLevel.LOW;
        };
    }

    private InfectionType parseInfectionType(String type) {
        if (!StringUtils.hasText(type)) {
            return InfectionType.OTHER;
        }
        String normalized = type.toLowerCase();
        if (normalized.contains("vap")) {
            return InfectionType.VAP;
        }
        if (normalized.contains("clabsi")) {
            return InfectionType.CLABSI;
        }
        if (normalized.contains("cauti") || normalized.contains("尿路")) {
            return InfectionType.CAUTI;
        }
        return InfectionType.OTHER;
    }
}
