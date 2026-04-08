package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.agent.WarningAgent;
import com.zzhy.yg_ai.ai.prompt.WarningAgentPrompt;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.enums.InfectionBodySite;
import com.zzhy.yg_ai.domain.enums.InfectionCaseState;
import com.zzhy.yg_ai.domain.enums.InfectionJudgePolarity;
import com.zzhy.yg_ai.domain.enums.InfectionNosocomialLikelihood;
import com.zzhy.yg_ai.domain.enums.InfectionNodeType;
import com.zzhy.yg_ai.domain.enums.InfectionWarningLevel;
import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.InfectionJudgePrecompute;
import com.zzhy.yg_ai.domain.model.JudgeDecisionResult;
import com.zzhy.yg_ai.domain.model.JudgeCatalogEvent;
import com.zzhy.yg_ai.domain.model.JudgeDecisionBuckets;
import com.zzhy.yg_ai.domain.model.JudgeEvidenceGroup;
import com.zzhy.yg_ai.service.InfectionJudgeService;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
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
    private static final Set<String> DECISION_STATUS = InfectionCaseState.allCodes();
    private static final Set<String> WARNING_LEVEL = InfectionWarningLevel.allCodes();
    private static final Set<String> NOSOCOMIAL_LIKELIHOOD = InfectionNosocomialLikelihood.allCodes();
    private static final Set<String> PRIMARY_SITE = Set.of("urinary", "respiratory", "genital", "abdominal", "bloodstream", "unknown");
    private static final Set<String> INFECTION_POLARITY = InfectionJudgePolarity.allCodes();

    private final WarningAgent warningAgent;
    private final InfectionLlmNodeRunService infectionLlmNodeRunService;
    private final ObjectMapper objectMapper;

    @Override
    public JudgeDecisionResult judge(InfectionEvidencePacket packet, LocalDateTime judgeTime) {
        InfectionEvidencePacket safePacket = packet == null ? InfectionEvidencePacket.builder().build() : packet;
        String reqno = safePacket.reqno();
        String promptVersion = WarningAgentPrompt.CASE_JUDGE_PROMPT_VERSION;
        String inputPayload = writeJson(safePacket);
        InfectionLlmNodeRunEntity runEntity = buildPendingRun(reqno, inputPayload, promptVersion);
        infectionLlmNodeRunService.createPendingRun(runEntity);
        long startedAt = System.currentTimeMillis();
        String rawOutput = null;
        try {
            String prompt = WarningAgentPrompt.buildCaseJudgePrompt();
            rawOutput = warningAgent.callCaseJudge(prompt, inputPayload);
            JudgeDecisionResult parsed = parseJudgeDecision(rawOutput, safePacket, judgeTime);
            infectionLlmNodeRunService.markSuccess(
                    runEntity.getId(),
                    rawOutput,
                    buildJudgeRunPayload(safePacket, parsed, false, null),
                    extractConfidence(rawOutput),
                    System.currentTimeMillis() - startedAt
            );
            return parsed;
        } catch (Exception e) {
            JudgeDecisionResult fallback = buildFallbackDecision(safePacket, judgeTime);
            infectionLlmNodeRunService.markFailed(
                    runEntity.getId(),
                    rawOutput,
                    buildJudgeRunPayload(safePacket, fallback, true, e.getMessage()),
                    "CASE_JUDGE_FAILED",
                    e.getMessage(),
                    System.currentTimeMillis() - startedAt
            );
            log.warn("院感法官节点调用失败，使用 fallback，reqno={}", reqno, e);
            return fallback;
        }
    }

    private InfectionLlmNodeRunEntity buildPendingRun(String reqno, String inputPayload, String promptVersion) {
        InfectionLlmNodeRunEntity entity = new InfectionLlmNodeRunEntity();
        entity.setReqno(reqno);
        entity.setNodeRunKey(UUID.randomUUID().toString());
        entity.setNodeType(InfectionNodeType.CASE_JUDGE.name());
        entity.setNodeName("infection-case-judge");
        entity.setPromptVersion(promptVersion);
        entity.setModelName(MODEL_NAME);
        entity.setInputPayload(inputPayload);
        return entity;
    }

    private String buildJudgeRunPayload(InfectionEvidencePacket packet,
                                        JudgeDecisionResult decision,
                                        boolean fallbackUsed,
                                        String errorMessage) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode stats = objectMapper.createObjectNode();
        List<JudgeEvidenceGroup> groups = packet == null || packet.evidenceGroups() == null ? List.of() : packet.evidenceGroups();
        JudgeDecisionBuckets buckets = packet == null ? null : packet.decisionBuckets();
        Set<String> validKeys = collectValidKeys(packet == null ? InfectionEvidencePacket.builder().build() : packet);
        stats.put("raw_group_count", groups.size());
        stats.put("support_group_count", buckets == null ? 0 : buckets.supportGroupIds().size());
        stats.put("against_group_count", buckets == null ? 0 : buckets.againstGroupIds().size());
        stats.put("risk_group_count", buckets == null ? 0 : buckets.riskGroupIds().size());
        stats.put("new_group_count", buckets == null ? 0 : buckets.newGroupIds().size());
        stats.put("referenced_key_count", validKeys.size());
        stats.put("selected_supporting_key_count", decision == null || decision.newSupportingKeys() == null ? 0 : decision.newSupportingKeys().size());
        stats.put("selected_against_key_count", decision == null || decision.newAgainstKeys() == null ? 0 : decision.newAgainstKeys().size());
        stats.put("selected_risk_key_count", decision == null || decision.newRiskKeys() == null ? 0 : decision.newRiskKeys().size());
        stats.put("dismissed_key_count", decision == null || decision.dismissedKeys() == null ? 0 : decision.dismissedKeys().size());
        stats.put("fallback_used", fallbackUsed);
        root.set("stats", stats);
        if (decision != null) {
            root.set("decision", objectMapper.valueToTree(decision));
        }
        if (StringUtils.hasText(errorMessage)) {
            root.put("error_message", errorMessage);
        }
        return writeJson(root);
    }

    private JudgeDecisionResult parseJudgeDecision(String rawOutput,
                                                   InfectionEvidencePacket packet,
                                                   LocalDateTime judgeTime) throws Exception {
        JsonNode root = objectMapper.readTree(rawOutput);
        String decisionStatus = normalizeEnumText(root.path("decisionStatus").asText(""));
        String warningLevel = normalizeEnumText(root.path("warningLevel").asText(""));
        String primarySite = normalizeEnumText(root.path("primarySite").asText(""));
        String nosocomialLikelihood = normalizeEnumText(root.path("nosocomialLikelihood").asText(""));
        String infectionPolarity = normalizeEnumText(root.path("infectionPolarity").asText(""));
        String decisionReason = normalizeText(root.path("decisionReason").asText(""));
        if (!StringUtils.hasText(decisionStatus) || !StringUtils.hasText(warningLevel)
                || !StringUtils.hasText(primarySite) || !StringUtils.hasText(nosocomialLikelihood)
                || !StringUtils.hasText(infectionPolarity)
                || !StringUtils.hasText(decisionReason)) {
            throw new IllegalStateException("judge output missing required fields");
        }
        validateEnum(decisionStatus, DECISION_STATUS, "decisionStatus");
        validateEnum(warningLevel, WARNING_LEVEL, "warningLevel");
        validateEnum(primarySite, PRIMARY_SITE, "primarySite");
        validateEnum(nosocomialLikelihood, NOSOCOMIAL_LIKELIHOOD, "nosocomialLikelihood");
        validateEnum(infectionPolarity, INFECTION_POLARITY, "infectionPolarity");

        Set<String> validKeys = collectValidKeys(packet);
        InfectionJudgePrecompute precomputed = packet.precomputed();

        return JudgeDecisionResult.builder()
                .decisionStatus(decisionStatus)
                .warningLevel(warningLevel)
                .primarySite(primarySite)
                .nosocomialLikelihood(nosocomialLikelihood)
                .newOnsetFlag(precomputed == null ? Boolean.FALSE : precomputed.newOnsetFlag())
                .after48hFlag(precomputed == null ? "unknown" : precomputed.after48hFlag())
                .procedureRelatedFlag(precomputed == null ? Boolean.FALSE : precomputed.procedureRelatedFlag())
                .deviceRelatedFlag(precomputed == null ? Boolean.FALSE : precomputed.deviceRelatedFlag())
                .infectionPolarity(infectionPolarity)
                .decisionReason(decisionReason)
                .newSupportingKeys(readStringArray(root.path("newSupportingKeys"), validKeys))
                .newAgainstKeys(readStringArray(root.path("newAgainstKeys"), validKeys))
                .newRiskKeys(readStringArray(root.path("newRiskKeys"), validKeys))
                .dismissedKeys(readStringArray(root.path("dismissedKeys"), validKeys))
                .requiresFollowUp(resolveFollowUp(root.get("requiresFollowUp"), decisionStatus))
                .nextSuggestedJudgeAt(resolveNextJudgeTime(root.path("nextSuggestedJudgeAt").asText(""), judgeTime, decisionStatus))
                .resultVersion(parseResultVersion(root.path("resultVersion"), packet))
                .build();
    }

    private JudgeDecisionResult buildFallbackDecision(InfectionEvidencePacket packet, LocalDateTime judgeTime) {
        JudgeDecisionBuckets buckets = packet.decisionBuckets();
        boolean hasSupporting = buckets != null && !CollectionUtils.isEmpty(buckets.supportGroupIds());
        boolean hasAgainst = buckets != null && !CollectionUtils.isEmpty(buckets.againstGroupIds());
        boolean hasRisk = buckets != null && !CollectionUtils.isEmpty(buckets.riskGroupIds());
        boolean hasNew = buckets != null && !CollectionUtils.isEmpty(buckets.newGroupIds());
        InfectionJudgePrecompute precomputed = packet.precomputed();
        String fallbackPolarity = hasSupporting
                ? InfectionJudgePolarity.SUPPORT.code()
                : (hasAgainst ? InfectionJudgePolarity.AGAINST.code() : InfectionJudgePolarity.UNCERTAIN.code());
        if (!hasSupporting && !hasAgainst && !hasRisk && !hasNew) {
            return JudgeDecisionResult.builder()
                    .decisionStatus(InfectionCaseState.NO_RISK.code())
                    .warningLevel(InfectionWarningLevel.NONE.code())
                    .primarySite(InfectionBodySite.UNKNOWN.code())
                    .nosocomialLikelihood(InfectionNosocomialLikelihood.LOW.code())
                    .newOnsetFlag(precomputed == null ? Boolean.FALSE : precomputed.newOnsetFlag())
                    .after48hFlag(precomputed == null ? InfectionBodySite.UNKNOWN.code() : precomputed.after48hFlag())
                    .procedureRelatedFlag(precomputed == null ? Boolean.FALSE : precomputed.procedureRelatedFlag())
                    .deviceRelatedFlag(precomputed == null ? Boolean.FALSE : precomputed.deviceRelatedFlag())
                    .infectionPolarity(InfectionJudgePolarity.UNCERTAIN.code())
                    .decisionReason("当前无新增支持、反证或风险背景事件。")
                    .requiresFollowUp(Boolean.FALSE)
                    .nextSuggestedJudgeAt(judgeTime == null ? null : judgeTime.plusMinutes(30))
                    .resultVersion(packet.snapshotVersion() == null ? 1 : packet.snapshotVersion() + 1)
                    .build();
        }

        String primarySite = packet.judgeContext() == null || CollectionUtils.isEmpty(packet.judgeContext().majorSites())
                ? InfectionBodySite.UNKNOWN.code()
                : packet.judgeContext().majorSites().get(0);
        List<String> supportKeys = groupKeys(packet, buckets == null ? List.of() : buckets.supportGroupIds());
        List<String> againstKeys = groupKeys(packet, buckets == null ? List.of() : buckets.againstGroupIds());
        List<String> riskKeys = groupKeys(packet, buckets == null ? List.of() : buckets.riskGroupIds());

        return JudgeDecisionResult.builder()
                .decisionStatus(InfectionCaseState.CANDIDATE.code())
                .warningLevel(hasSupporting ? InfectionWarningLevel.MEDIUM.code() : InfectionWarningLevel.LOW.code())
                .primarySite(primarySite)
                .nosocomialLikelihood(hasRisk && hasSupporting ? InfectionNosocomialLikelihood.MEDIUM.code() : InfectionNosocomialLikelihood.LOW.code())
                .newOnsetFlag(precomputed == null ? Boolean.FALSE : precomputed.newOnsetFlag())
                .after48hFlag(precomputed == null ? InfectionBodySite.UNKNOWN.code() : precomputed.after48hFlag())
                .procedureRelatedFlag(precomputed == null ? Boolean.FALSE : precomputed.procedureRelatedFlag())
                .deviceRelatedFlag(precomputed == null ? Boolean.FALSE : precomputed.deviceRelatedFlag())
                .infectionPolarity(fallbackPolarity)
                .decisionReason("当前存在新增事件，需由第二层法官进一步综合裁决。")
                .newSupportingKeys(supportKeys)
                .newAgainstKeys(againstKeys)
                .newRiskKeys(riskKeys)
                .requiresFollowUp(Boolean.TRUE)
                .nextSuggestedJudgeAt(judgeTime == null ? null : judgeTime.plusMinutes(hasSupporting ? 10 : 30))
                .resultVersion(packet.snapshotVersion() == null ? 1 : packet.snapshotVersion() + 1)
                .build();
    }

    private List<String> readStringArray(JsonNode node, Set<String> validKeys) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            String text = normalizeText(item == null ? null : item.asText(""));
            if (StringUtils.hasText(text) && (validKeys == null || validKeys.isEmpty() || validKeys.contains(text))) {
                result.add(text);
            }
        });
        return List.copyOf(result);
    }

    private Boolean parseBoolean(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String text = node.asText("");
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return Boolean.parseBoolean(text.trim());
    }

    private LocalDateTime parseDateTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private LocalDateTime resolveNextJudgeTime(String raw, LocalDateTime judgeTime, String decisionStatus) {
        LocalDateTime parsed = parseDateTime(raw);
        if (parsed != null) {
            return parsed;
        }
        if (judgeTime == null) {
            return null;
        }
        InfectionCaseState state = InfectionCaseState.fromCodeOrDefault(decisionStatus, null);
        if (state == InfectionCaseState.WARNING) {
            return judgeTime.plusMinutes(10);
        }
        if (state == InfectionCaseState.CANDIDATE) {
            return judgeTime.plusMinutes(15);
        }
        return judgeTime.plusMinutes(30);
    }

    private Boolean resolveFollowUp(JsonNode node, String decisionStatus) {
        Boolean parsed = parseBoolean(node);
        if (parsed != null) {
            return parsed;
        }
        InfectionCaseState state = InfectionCaseState.fromCodeOrDefault(decisionStatus, null);
        return state != null && state.isActiveRisk();
    }

    private int parseResultVersion(JsonNode node, InfectionEvidencePacket packet) {
        if (node != null && node.isInt()) {
            return Math.max(1, node.intValue());
        }
        return packet.snapshotVersion() == null ? 1 : packet.snapshotVersion() + 1;
    }

    private BigDecimal extractConfidence(String rawOutput) {
        try {
            JsonNode root = objectMapper.readTree(rawOutput);
            JsonNode confidenceNode = root.get("confidence");
            if (confidenceNode != null && confidenceNode.isNumber()) {
                return confidenceNode.decimalValue();
            }
        } catch (Exception e) {
            log.debug("extract judge confidence failed", e);
        }
        return null;
    }

    private String normalizeEnumText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }

    private void validateEnum(String value, Set<String> allowed, String fieldName) {
        if (!allowed.contains(value)) {
            throw new IllegalStateException("judge output invalid " + fieldName + ": " + value);
        }
    }

    private Set<String> collectValidKeys(InfectionEvidencePacket packet) {
        Set<String> result = new HashSet<>();
        if (packet == null || packet.eventCatalog() == null) {
            return result;
        }
        packet.eventCatalog().stream()
                .map(JudgeCatalogEvent::eventKey)
                .filter(StringUtils::hasText)
                .forEach(result::add);
        return result;
    }

    private List<String> groupKeys(InfectionEvidencePacket packet, List<String> groupIds) {
        if (packet == null || groupIds == null || groupIds.isEmpty() || packet.evidenceGroups() == null) {
            return List.of();
        }
        LinkedHashMap<String, String> keyMap = new LinkedHashMap<>();
        packet.evidenceGroups().stream()
                .filter(group -> groupIds.contains(group.groupId()))
                .map(group -> resolveGroupKey(group, packet))
                .filter(StringUtils::hasText)
                .forEach(key -> keyMap.putIfAbsent(key, key));
        return List.copyOf(keyMap.values());
    }

    private String resolveGroupKey(JudgeEvidenceGroup group, InfectionEvidencePacket packet) {
        if (group == null) {
            return null;
        }
        if (StringUtils.hasText(group.representativeEventKey())) {
            return group.representativeEventKey();
        }
        if (packet == null || packet.eventCatalog() == null) {
            return null;
        }
        return packet.eventCatalog().stream()
                .filter(event -> group.representativeEventId().equals(event.eventId()))
                .map(JudgeCatalogEvent::eventKey)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize case judge payload", e);
        }
    }
}
