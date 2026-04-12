package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.gateway.AiGateway;
import com.zzhy.yg_ai.ai.prompt.WarningPromptCatalog;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.enums.InfectionNodeType;
import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.JudgeDecisionResult;
import com.zzhy.yg_ai.domain.model.PreReviewAiSuggestion;
import com.zzhy.yg_ai.domain.model.PreReviewMissingEvidenceReminder;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import com.zzhy.yg_ai.service.InfectionJudgeService;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import com.zzhy.yg_ai.service.casejudge.InfectionCaseJudgeSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 第二层法官节点。
 * 当前使用单一 LLM 裁决，若输出非法则回退到确定性最小结果，避免整条 CASE_RECOMPUTE 链失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfectionJudgeServiceImpl implements InfectionJudgeService {

    private static final String MODEL_NAME = "warning-agent-chat-model";
    private static final String PRE_REVIEW_DEMO_NODE_NAME = "infection-pre-review-demo-extension";
    private static final int MAX_PRE_REVIEW_ITEMS = 5;
    private static final int MAX_PRE_REVIEW_TEXT_LENGTH = 200;

    private final AiGateway aiGateway;
    private final InfectionLlmNodeRunService infectionLlmNodeRunService;
    private final InfectionCaseJudgeSupport infectionCaseJudgeSupport;
    private final ObjectMapper objectMapper;

    @Override
    public JudgeDecisionResult judge(InfectionEvidencePacket packet, LocalDateTime judgeTime) {
        InfectionEvidencePacket safePacket = packet == null ? InfectionEvidencePacket.builder().build() : packet;
        String reqno = safePacket.reqno();
        String promptVersion = WarningPromptCatalog.CASE_JUDGE_PROMPT_VERSION;
        String inputPayload = infectionCaseJudgeSupport.buildInputPayload(safePacket);
        InfectionLlmNodeRunEntity runEntity = buildPendingRun(reqno, inputPayload, promptVersion);
        infectionLlmNodeRunService.createPendingRun(runEntity);
        long startedAt = System.currentTimeMillis();
        String rawOutput = null;
        try {
            String prompt = WarningPromptCatalog.buildCaseJudgePrompt();
            rawOutput = aiGateway.callSystem(
                    PipelineStage.CASE_RECOMPUTE,
                    InfectionNodeType.CASE_JUDGE.name(),
                    prompt,
                    inputPayload
            );
            JudgeDecisionResult parsed = infectionCaseJudgeSupport.parseDecision(rawOutput, safePacket, judgeTime);
            infectionLlmNodeRunService.markSuccess(
                    runEntity.getId(),
                    rawOutput,
                    infectionCaseJudgeSupport.buildRunPayload(safePacket, parsed, false, null),
                    infectionCaseJudgeSupport.extractConfidence(rawOutput),
                    System.currentTimeMillis() - startedAt
            );
            return enrichDemoPreReviewBlocks(parsed, safePacket);
        } catch (Exception e) {
            JudgeDecisionResult fallback = infectionCaseJudgeSupport.buildFallbackDecision(safePacket, judgeTime);
            try {
                infectionLlmNodeRunService.markFailed(
                        runEntity.getId(),
                        rawOutput,
                        infectionCaseJudgeSupport.buildRunPayload(safePacket, fallback, true, e.getMessage()),
                        "CASE_JUDGE_FAILED",
                        e.getMessage(),
                        System.currentTimeMillis() - startedAt
                );
            } catch (Exception markFailedError) {
                e.addSuppressed(markFailedError);
                log.error("Failed to persist case judge failure audit, reqno={}, runId={}",
                        reqno, runEntity.getId(), markFailedError);
            }
            log.warn("院感法官节点调用失败，使用 fallback，reqno={}", reqno, e);
            return fallback;
        }
    }

    private InfectionLlmNodeRunEntity buildPendingRun(String reqno, String inputPayload, String promptVersion) {
        return buildPendingRun(reqno, inputPayload, promptVersion, InfectionNodeType.CASE_JUDGE, "infection-case-judge");
    }

    private InfectionLlmNodeRunEntity buildPendingRun(String reqno,
                                                      String inputPayload,
                                                      String promptVersion,
                                                      InfectionNodeType nodeType,
                                                      String nodeName) {
        InfectionLlmNodeRunEntity entity = new InfectionLlmNodeRunEntity();
        entity.setReqno(reqno);
        entity.setNodeRunKey(UUID.randomUUID().toString());
        entity.setNodeType(nodeType.name());
        entity.setNodeName(nodeName);
        entity.setPromptVersion(promptVersion);
        entity.setModelName(MODEL_NAME);
        entity.setInputPayload(inputPayload);
        return entity;
    }

    private JudgeDecisionResult enrichDemoPreReviewBlocks(JudgeDecisionResult decision, InfectionEvidencePacket packet) {
        if (decision == null) {
            return null;
        }
        InfectionEvidencePacket safePacket = packet == null ? InfectionEvidencePacket.builder().build() : packet;
        String reqno = safePacket.reqno();
        String promptVersion = WarningPromptCatalog.PRE_REVIEW_DEMO_EXTENSION_PROMPT_VERSION;
        String inputPayload = null;
        String rawOutput = null;
        InfectionLlmNodeRunEntity runEntity = null;
        long startedAt = System.currentTimeMillis();
        try {
            inputPayload = buildPreReviewDemoExtensionInput(decision, safePacket);
            runEntity = buildPendingRun(
                    reqno,
                    inputPayload,
                    promptVersion,
                    InfectionNodeType.EXPLANATION_GENERATOR,
                    PRE_REVIEW_DEMO_NODE_NAME
            );
            infectionLlmNodeRunService.createPendingRun(runEntity);
            rawOutput = aiGateway.callSystem(
                    PipelineStage.CASE_RECOMPUTE,
                    InfectionNodeType.EXPLANATION_GENERATOR.name(),
                    WarningPromptCatalog.buildPreReviewDemoExtensionPrompt(),
                    inputPayload
            );
            JudgeDecisionResult enriched = parsePreReviewDemoExtension(rawOutput, decision);
            infectionLlmNodeRunService.markSuccess(
                    runEntity.getId(),
                    rawOutput,
                    buildPreReviewDemoExtensionRunPayload(enriched),
                    null,
                    System.currentTimeMillis() - startedAt
            );
            return enriched;
        } catch (Exception e) {
            JudgeDecisionResult emptyResult = decision.toBuilder()
                    .missingEvidenceReminders(List.of())
                    .aiSuggestions(List.of())
                    .build();
            markPreReviewDemoExtensionFailed(runEntity, rawOutput, emptyResult, e, startedAt);
            log.warn("院感预审演示补充内容生成失败，返回空数组，reqno={}", reqno, e);
            return emptyResult;
        }
    }

    private String buildPreReviewDemoExtensionInput(JudgeDecisionResult decision, InfectionEvidencePacket packet) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("decision", objectMapper.valueToTree(decision));
        root.set("eventCatalog", objectMapper.valueToTree(packet.eventCatalog()));
        root.set("evidenceGroups", objectMapper.valueToTree(packet.evidenceGroups()));
        root.set("decisionBuckets", objectMapper.valueToTree(packet.decisionBuckets()));
        root.set("precomputed", objectMapper.valueToTree(packet.precomputed()));
        root.set("judgeContext", objectMapper.valueToTree(packet.judgeContext()));
        root.set("recentChanges", objectMapper.valueToTree(packet.recentChanges()));
        return objectMapper.writeValueAsString(root);
    }

    private JudgeDecisionResult parsePreReviewDemoExtension(String rawOutput, JudgeDecisionResult decision) throws Exception {
        JsonNode root = objectMapper.readTree(rawOutput);
        List<PreReviewMissingEvidenceReminder> reminders = readMissingEvidenceReminders(root.path("missingEvidenceReminders"));
        List<PreReviewAiSuggestion> suggestions = readAiSuggestions(root.path("aiSuggestions"));
        return decision.toBuilder()
                .missingEvidenceReminders(reminders)
                .aiSuggestions(suggestions)
                .build();
    }

    private List<PreReviewMissingEvidenceReminder> readMissingEvidenceReminders(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<PreReviewMissingEvidenceReminder> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= MAX_PRE_REVIEW_ITEMS) {
                break;
            }
            String title = normalizeText(item.path("title").asText(""));
            String message = normalizeText(item.path("message").asText(""));
            if (!StringUtils.hasText(title) && !StringUtils.hasText(message)) {
                continue;
            }
            String level = defaultIfBlank(normalizeText(item.path("level").asText("")), "warning");
            result.add(new PreReviewMissingEvidenceReminder(level, title, message));
        }
        return List.copyOf(result);
    }

    private List<PreReviewAiSuggestion> readAiSuggestions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<PreReviewAiSuggestion> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= MAX_PRE_REVIEW_ITEMS) {
                break;
            }
            String text = normalizeText(item.path("text").asText(""));
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String priority = defaultIfBlank(normalizeText(item.path("priority").asText("")), "medium");
            String category = defaultIfBlank(normalizeText(item.path("category").asText("")), "review");
            result.add(new PreReviewAiSuggestion(priority, category, text));
        }
        return List.copyOf(result);
    }

    private void markPreReviewDemoExtensionFailed(InfectionLlmNodeRunEntity runEntity,
                                                  String rawOutput,
                                                  JudgeDecisionResult emptyResult,
                                                  Exception error,
                                                  long startedAt) {
        if (runEntity == null || runEntity.getId() == null) {
            return;
        }
        try {
            infectionLlmNodeRunService.markFailed(
                    runEntity.getId(),
                    rawOutput,
                    buildPreReviewDemoExtensionRunPayload(emptyResult),
                    "PRE_REVIEW_DEMO_EXTENSION_FAILED",
                    error.getMessage(),
                    System.currentTimeMillis() - startedAt
            );
        } catch (Exception markFailedError) {
            error.addSuppressed(markFailedError);
            log.error("Failed to persist pre review demo extension failure audit, reqno={}, runId={}",
                    runEntity.getReqno(), runEntity.getId(), markFailedError);
        }
    }

    private String buildPreReviewDemoExtensionRunPayload(JudgeDecisionResult decision) {
        if (decision == null) {
            return null;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.set("missingEvidenceReminders", objectMapper.valueToTree(decision.missingEvidenceReminders()));
            root.set("aiSuggestions", objectMapper.valueToTree(decision.aiSuggestions()));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        return text.length() <= MAX_PRE_REVIEW_TEXT_LENGTH
                ? text
                : text.substring(0, MAX_PRE_REVIEW_TEXT_LENGTH);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
