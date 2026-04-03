package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionBodySite;
import com.zzhy.yg_ai.domain.enums.InfectionCertainty;
import com.zzhy.yg_ai.domain.enums.InfectionClinicalMeaning;
import com.zzhy.yg_ai.domain.enums.InfectionEventStatus;
import com.zzhy.yg_ai.domain.enums.InfectionEventSubtype;
import com.zzhy.yg_ai.domain.enums.InfectionEventType;
import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.enums.InfectionPolarity;
import com.zzhy.yg_ai.domain.enums.InfectionSeverity;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import com.zzhy.yg_ai.service.EventNormalizerService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventNormalizerServiceImpl implements EventNormalizerService {

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = DateTimeUtils.DATE_TIME_PARSE_FORMATTERS;

    private static final Set<String> STRUCTURED_SOURCE_SECTIONS = Set.of(
            "diagnosis",
            "vital_signs",
            "lab_results",
            "imaging",
            "doctor_orders",
            "use_medicine",
            "transfer",
            "operation"
    );

    private static final Set<String> EVIDENCE_TIERS = Set.of("hard", "moderate", "weak");

    private static final Set<String> EVIDENCE_ROLES = Set.of("support", "against", "risk_only", "background");

    private static final Set<String> ABNORMAL_FLAGS = Set.of(
            "high", "low", "positive", "negative", "abnormal", "normal"
    );

    private static final Map<String, String> EVENT_TYPE_ALIASES = Map.of(
            "lab", "lab_result",
            "image", "imaging",
            "microbe", "microbiology"
    );

    private static final Map<String, String> EVENT_SUBTYPE_ALIASES = Map.of(
            "culture_order", "culture_ordered",
            "culture_pos", "culture_positive",
            "antibiotic_upgrade", "antibiotic_upgraded"
    );

    private static final Map<String, String> CLINICAL_MEANING_ALIASES = Map.of(
            "support", "infection_support",
            "against", "infection_against",
            "uncertain", "infection_uncertain"
    );

    private static final Map<String, Set<String>> STRUCTURED_SECTION_EVENT_TYPES = new LinkedHashMap<>();

    static {
        STRUCTURED_SECTION_EVENT_TYPES.put("diagnosis", Set.of(InfectionEventType.DIAGNOSIS.code()));
        STRUCTURED_SECTION_EVENT_TYPES.put("vital_signs", Set.of(InfectionEventType.VITAL_SIGN.code()));
        STRUCTURED_SECTION_EVENT_TYPES.put("lab_results", Set.of(
                InfectionEventType.LAB_RESULT.code(),
                InfectionEventType.LAB_PANEL.code(),
                InfectionEventType.MICROBIOLOGY.code()
        ));
        STRUCTURED_SECTION_EVENT_TYPES.put("imaging", Set.of(InfectionEventType.IMAGING.code()));
        STRUCTURED_SECTION_EVENT_TYPES.put("doctor_orders", Set.of(
                InfectionEventType.ORDER.code(),
                InfectionEventType.DEVICE.code(),
                InfectionEventType.PROCEDURE.code()
        ));
        STRUCTURED_SECTION_EVENT_TYPES.put("use_medicine", Set.of(InfectionEventType.ORDER.code()));
        STRUCTURED_SECTION_EVENT_TYPES.put("transfer", Set.of(
                InfectionEventType.PROBLEM.code(),
                InfectionEventType.ASSESSMENT.code()
        ));
        STRUCTURED_SECTION_EVENT_TYPES.put("operation", Set.of(
                InfectionEventType.PROCEDURE.code(),
                InfectionEventType.ORDER.code()
        ));
    }

    private final ObjectMapper objectMapper;

    @Override
    public List<NormalizedInfectionEvent> normalize(EvidenceBlock block,
                                                    String extractorOutputJson,
                                                    InfectionExtractorType extractorType,
                                                    String promptVersion,
                                                    String modelName,
                                                    BigDecimal confidence) {
        if (block == null || block.contextOnly()) {
            return List.of();
        }
        JsonNode root = parseRoot(extractorOutputJson);
        if (root == null || !root.isObject()) {
            return List.of();
        }
        String status = normalizeStatus(root.path("status").asText(""));
        if (!StringUtils.hasText(status)) {
            log.warn("EventNormalizer response status 非法, rawDataId={}", block.rawDataId());
            return List.of();
        }
        if ("skipped".equals(status)) {
            if (root.path("events").isArray() && root.path("events").isEmpty()) {
                return List.of();
            }
            log.warn("EventNormalizer response skipped 但 events 非空, rawDataId={}", block.rawDataId());
            return List.of();
        }
        BigDecimal responseConfidence = parseRootConfidence(root.path("confidence"));
        if (responseConfidence == null) {
            log.warn("EventNormalizer response confidence 非法, rawDataId={}", block.rawDataId());
            return List.of();
        }
        JsonNode eventsNode = root.path("events");
        if (!eventsNode.isArray() || eventsNode.isEmpty()) {
            return List.of();
        }

        List<NormalizedInfectionEvent> result = new ArrayList<>();
        for (JsonNode eventNode : eventsNode) {
            if (!eventNode.isObject()) {
                continue;
            }
            NormalizedInfectionEvent event = normalizeSingle(block,
                    (ObjectNode) eventNode,
                    extractorType,
                    promptVersion,
                    modelName,
                    responseConfidence,
                    confidence);
            if (event != null) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    private NormalizedInfectionEvent normalizeSingle(EvidenceBlock block,
                                                     ObjectNode source,
                                                     InfectionExtractorType extractorType,
                                                     String promptVersion,
                                                     String modelName,
                                                     BigDecimal responseConfidence,
                                                     BigDecimal persistedConfidence) {
        String eventType = normalizeEventType(source.path("event_type").asText(""));
        if (!StringUtils.hasText(eventType)) {
            return null;
        }
        String eventSubtype = normalizeSubtype(source.path("event_subtype").asText(""));
        if (source.hasNonNull("event_subtype") && !source.path("event_subtype").asText("").isBlank()
                && !StringUtils.hasText(eventSubtype)) {
            return null;
        }
        String bodySite = normalizeSiteStrict(source.path("body_site").asText(""));
        if (!StringUtils.hasText(bodySite)) {
            return null;
        }
        String clinicalMeaning = normalizeClinicalMeaning(source.path("clinical_meaning").asText(""));
        if (!StringUtils.hasText(clinicalMeaning)) {
            return null;
        }
        String evidenceTier = normalizeEvidenceTier(source.path("evidence_tier").asText(""));
        if (!StringUtils.hasText(evidenceTier)) {
            return null;
        }
        String evidenceRole = normalizeEvidenceRole(source.path("evidence_role").asText(""));
        if (!StringUtils.hasText(evidenceRole)) {
            return null;
        }
        String sourceSection = normalizeSourceSection(
                source.get("source_section"),
                EvidenceBlockType.STRUCTURED_FACT.equals(block.blockType())
        );
        if (EvidenceBlockType.STRUCTURED_FACT.equals(block.blockType()) && !StringUtils.hasText(sourceSection)) {
            return null;
        }
        if (!EvidenceBlockType.STRUCTURED_FACT.equals(block.blockType()) && sourceSection != null) {
            return null;
        }

        Boolean infectionRelated = parseBoolean(source.get("infection_related"));
        Boolean negationFlag = parseBoolean(source.get("negation_flag"));
        Boolean uncertaintyFlag = parseBoolean(source.get("uncertainty_flag"));
        if (infectionRelated == null || negationFlag == null || uncertaintyFlag == null) {
            return null;
        }
        if ("background".equals(evidenceRole) && Boolean.TRUE.equals(infectionRelated)) {
            return null;
        }

        String eventName = normalizeRequiredText(source.path("event_name").asText(""));
        String sourceText = normalizeRequiredText(source.path("source_text").asText(""));
        if (!StringUtils.hasText(eventName) || !StringUtils.hasText(sourceText)) {
            return null;
        }
        if (!isSourceTextTraceable(block, sourceSection, sourceText)) {
            return null;
        }
        if (!validateStructuredFactSectionRule(block.blockType(), sourceSection, eventType, evidenceTier, evidenceRole,
                sourceText)) {
            return null;
        }
        if (!validateSubtypeRule(eventType, eventSubtype)) {
            return null;
        }
        if (!validateCompletedResultRule(source, eventSubtype, sourceSection)) {
            return null;
        }
        if (!validateClinicalConsistency(source, eventSubtype)) {
            return null;
        }

        LocalDateTime eventTime = normalizeEventTime(source.path("event_time").asText(""));
        String polarity = inferPolarity(evidenceRole, negationFlag, uncertaintyFlag, clinicalMeaning);
        String certainty = inferCertainty(evidenceRole, uncertaintyFlag, block.blockType(), responseConfidence);
        String abnormalFlag = normalizeAbnormalFlag(source.get("abnormal_flag"));
        BigDecimal finalConfidence = persistedConfidence != null ? persistedConfidence : responseConfidence;

        NormalizedInfectionEvent event = new NormalizedInfectionEvent();
        event.setReqno(block.reqno());
        event.setRawDataId(block.rawDataId());
        event.setDataDate(block.dataDate());
        event.setSourceType(block.sourceType().code());
        event.setSourceRef(block.sourceRef());
        event.setEventType(eventType);
        event.setEventSubtype(eventSubtype);
        event.setEventCategory(resolveEventCategory(block.blockType()));
        event.setEventTime(eventTime);
        event.setSite(bodySite);
        event.setPolarity(polarity);
        event.setCertainty(certainty);
        event.setSeverity(InfectionSeverity.UNCLEAR.code());
        event.setIsHardFact(EvidenceBlockType.STRUCTURED_FACT.equals(block.blockType()));
        event.setIsActive(Boolean.TRUE);
        event.setTitle(eventName);
        event.setContent(sourceText);
        event.setExtractorType(extractorType == null ? null : extractorType.code());
        event.setPromptVersion(promptVersion);
        event.setModelName(modelName);
        event.setConfidence(finalConfidence);
        event.setStatus(InfectionEventStatus.ACTIVE.code());
        event.setEvidenceJson(buildEvidenceJson(block, source, sourceSection, sourceText));
        event.setAttributesJson(buildAttributesJson(source,
                clinicalMeaning,
                sourceSection,
                evidenceTier,
                evidenceRole,
                abnormalFlag,
                infectionRelated,
                negationFlag,
                uncertaintyFlag));
        event.setEventKey(buildEventKey(event, sourceSection));
        return event;
    }

    private JsonNode parseRoot(String extractorOutputJson) {
        if (!StringUtils.hasText(extractorOutputJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(extractorOutputJson);
        } catch (Exception e) {
            log.warn("EventNormalizer 解析 extractor 输出失败", e);
            return null;
        }
    }

    private String normalizeStatus(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return null;
        }
        String normalized = rawStatus.trim().toLowerCase(Locale.ROOT);
        return ("success".equals(normalized) || "skipped".equals(normalized)) ? normalized : null;
    }

    private BigDecimal parseRootConfidence(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                return null;
            }
            return value;
        }
        return null;
    }

    private String normalizeEventType(String rawType) {
        String normalized = normalizeAlias(rawType, EVENT_TYPE_ALIASES);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return InfectionEventType.fromCode(normalized).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeSubtype(String rawSubtype) {
        String normalized = normalizeAlias(rawSubtype, EVENT_SUBTYPE_ALIASES);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return InfectionEventSubtype.fromCode(normalized).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeSiteStrict(String rawSite) {
        if (!StringUtils.hasText(rawSite)) {
            return null;
        }
        try {
            return InfectionBodySite.fromCode(rawSite.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeClinicalMeaning(String rawMeaning) {
        String normalized = normalizeAlias(rawMeaning, CLINICAL_MEANING_ALIASES);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return InfectionClinicalMeaning.fromCode(normalized).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeEvidenceTier(String rawTier) {
        if (!StringUtils.hasText(rawTier)) {
            return null;
        }
        String normalized = rawTier.trim().toLowerCase(Locale.ROOT);
        return EVIDENCE_TIERS.contains(normalized) ? normalized : null;
    }

    private String normalizeEvidenceRole(String rawRole) {
        if (!StringUtils.hasText(rawRole)) {
            return null;
        }
        String normalized = rawRole.trim().toLowerCase(Locale.ROOT);
        return EVIDENCE_ROLES.contains(normalized) ? normalized : null;
    }

    private String normalizeSourceSection(JsonNode node, boolean required) {
        if (node == null || node.isNull()) {
            return required ? null : null;
        }
        String rawValue = node.asText("");
        if (!StringUtils.hasText(rawValue) || "null".equalsIgnoreCase(rawValue.trim())) {
            return required ? null : null;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        return STRUCTURED_SOURCE_SECTIONS.contains(normalized) ? normalized : "__invalid__";
    }

    private Boolean parseBoolean(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            String value = node.asText("").trim().toLowerCase(Locale.ROOT);
            if ("true".equals(value)) {
                return Boolean.TRUE;
            }
            if ("false".equals(value)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private String normalizeRequiredText(String rawText) {
        return StringUtils.hasText(rawText) ? rawText.trim() : null;
    }

    private LocalDateTime normalizeEventTime(String rawEventTime) {
        if (!StringUtils.hasText(rawEventTime)) {
            return null;
        }
        String value = rawEventTime.trim();
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return DateTimeUtils.truncateToMillis(LocalDateTime.parse(value, formatter));
            } catch (DateTimeParseException ignore) {
                // try next formatter
            }
        }
        return null;
    }

    private boolean validateStructuredFactSectionRule(EvidenceBlockType blockType,
                                                      String sourceSection,
                                                      String eventType,
                                                      String evidenceTier,
                                                      String evidenceRole,
                                                      String sourceText) {
        if (!EvidenceBlockType.STRUCTURED_FACT.equals(blockType)) {
            return true;
        }
        Set<String> allowedTypes = STRUCTURED_SECTION_EVENT_TYPES.get(sourceSection);
        if (allowedTypes == null || !allowedTypes.contains(eventType)) {
            return false;
        }
        if ("vital_signs".equals(sourceSection) && "hard".equals(evidenceTier)) {
            return false;
        }
        if ("diagnosis".equals(sourceSection) && "hard".equals(evidenceTier)) {
            return false;
        }
        if ("transfer".equals(sourceSection) && "support".equals(evidenceRole)) {
            return false;
        }
        if ("operation".equals(sourceSection) && "support".equals(evidenceRole)
                && !containsAny(sourceText, "感染", "脓", "发热", "炎")) {
            return false;
        }
        return true;
    }

    private boolean validateSubtypeRule(String eventType, String eventSubtype) {
        if (!StringUtils.hasText(eventSubtype)) {
            return true;
        }
        try {
            InfectionEventSubtype subtype = InfectionEventSubtype.fromCode(eventSubtype);
            return subtype.eventType().code().equals(eventType);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean validateClinicalConsistency(ObjectNode source, String eventSubtype) {
        Boolean infectionRelated = parseBoolean(source.get("infection_related"));
        Boolean negationFlag = parseBoolean(source.get("negation_flag"));
        Boolean uncertaintyFlag = parseBoolean(source.get("uncertainty_flag"));
        String evidenceRole = normalizeEvidenceRole(source.path("evidence_role").asText(""));
        String clinicalMeaning = normalizeClinicalMeaning(source.path("clinical_meaning").asText(""));

        if ("background".equals(evidenceRole) && Boolean.TRUE.equals(infectionRelated)) {
            return false;
        }
        if ("against".equals(evidenceRole) && Boolean.FALSE.equals(negationFlag)
                && !InfectionClinicalMeaning.INFECTION_AGAINST.code().equals(clinicalMeaning)
                && !defaultIfBlank(eventSubtype, "").contains("contamination")
                && !defaultIfBlank(eventSubtype, "").contains("colonization")) {
            return false;
        }
        if ("risk_only".equals(evidenceRole) && Boolean.TRUE.equals(uncertaintyFlag)
                && InfectionClinicalMeaning.INFECTION_SUPPORT.code().equals(clinicalMeaning)) {
            return false;
        }
        return true;
    }

    private boolean validateCompletedResultRule(ObjectNode source, String eventSubtype, String sourceSection) {
        if (!"culture_ordered".equals(eventSubtype)) {
            return true;
        }
        if (!"lab_results".equals(sourceSection)) {
            return true;
        }
        String sourceText = defaultIfBlank(source.path("source_text").asText(""), "");
        return !containsAny(sourceText, "阳性", "阴性", "异常", "正常");
    }

    private boolean isSourceTextTraceable(EvidenceBlock block, String sourceSection, String sourceText) {
        JsonNode payload = parsePayload(block.payloadJson());
        if (payload == null) {
            return false;
        }
        JsonNode searchNode;
        if (EvidenceBlockType.STRUCTURED_FACT.equals(block.blockType())) {
            if (!StringUtils.hasText(sourceSection)) {
                return false;
            }
            searchNode = payload.path("data").path(sourceSection);
        } else {
            searchNode = payload;
        }
        if (searchNode == null || searchNode.isMissingNode() || searchNode.isNull()) {
            return false;
        }
        String haystack = normalizeForSearch(writeJson(searchNode));
        String needle = normalizeForSearch(sourceText);
        return StringUtils.hasText(needle) && haystack.contains(needle);
    }

    private JsonNode parsePayload(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            log.warn("EventNormalizer 解析 block payload 失败", e);
            return null;
        }
    }

    private String inferPolarity(String evidenceRole,
                                 boolean negationFlag,
                                 boolean uncertaintyFlag,
                                 String clinicalMeaning) {
        if ("against".equals(evidenceRole) || negationFlag
                || InfectionClinicalMeaning.INFECTION_AGAINST.code().equals(clinicalMeaning)) {
            return InfectionPolarity.NEGATIVE.code();
        }
        if (uncertaintyFlag || InfectionClinicalMeaning.INFECTION_UNCERTAIN.code().equals(clinicalMeaning)) {
            return InfectionPolarity.UNCERTAIN.code();
        }
        if ("support".equals(evidenceRole)
                || InfectionClinicalMeaning.INFECTION_SUPPORT.code().equals(clinicalMeaning)) {
            return InfectionPolarity.POSITIVE.code();
        }
        return InfectionPolarity.NEUTRAL.code();
    }

    private String inferCertainty(String evidenceRole,
                                  boolean uncertaintyFlag,
                                  EvidenceBlockType blockType,
                                  BigDecimal confidence) {
        if ("risk_only".equals(evidenceRole)) {
            return InfectionCertainty.RISK_ONLY.code();
        }
        if (uncertaintyFlag) {
            return InfectionCertainty.POSSIBLE.code();
        }
        if (confidence != null && confidence.compareTo(new BigDecimal("0.60")) < 0
                && !EvidenceBlockType.STRUCTURED_FACT.equals(blockType)) {
            return InfectionCertainty.POSSIBLE.code();
        }
        return InfectionCertainty.CONFIRMED.code();
    }

    private String resolveEventCategory(EvidenceBlockType blockType) {
        return switch (blockType) {
            case STRUCTURED_FACT -> "fact";
            case CLINICAL_TEXT -> "text";
            case MID_SEMANTIC -> "semantic";
            case TIMELINE_CONTEXT -> "context";
        };
    }

    private String normalizeAbnormalFlag(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return ABNORMAL_FLAGS.contains(normalized) ? normalized : null;
    }

    private String buildEvidenceJson(EvidenceBlock block,
                                     ObjectNode source,
                                     String sourceSection,
                                     String sourceText) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("block_key", block.blockKey());
        evidence.put("block_type", block.blockType().name());
        evidence.put("source_ref", block.sourceRef());
        if (StringUtils.hasText(sourceSection)) {
            evidence.put("source_section", sourceSection);
        } else {
            evidence.putNull("source_section");
        }
        evidence.put("source_text", sourceText);
        evidence.set("raw_event", source.deepCopy());
        return writeJson(evidence);
    }

    private String buildAttributesJson(ObjectNode source,
                                       String clinicalMeaning,
                                       String sourceSection,
                                       String evidenceTier,
                                       String evidenceRole,
                                       String abnormalFlag,
                                       boolean infectionRelated,
                                       boolean negationFlag,
                                       boolean uncertaintyFlag) {
        ObjectNode attributes = objectMapper.createObjectNode();
        copyIfPresent(source, attributes, "event_value");
        copyIfPresent(source, attributes, "event_unit");
        if (StringUtils.hasText(abnormalFlag)) {
            attributes.put("abnormal_flag", abnormalFlag);
        }
        attributes.put("infection_related", infectionRelated);
        attributes.put("negation_flag", negationFlag);
        attributes.put("uncertainty_flag", uncertaintyFlag);
        attributes.put("clinical_meaning", clinicalMeaning);
        attributes.put("evidence_tier", evidenceTier);
        attributes.put("evidence_role", evidenceRole);
        if (StringUtils.hasText(sourceSection)) {
            attributes.put("source_section", sourceSection);
        }
        return writeJson(attributes);
    }

    private void copyIfPresent(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode node = source.get(fieldName);
        if (node != null && !node.isNull()) {
            target.set(fieldName, node.deepCopy());
        }
    }

    private String buildEventKey(NormalizedInfectionEvent event, String sourceSection) {
        String module = defaultIfBlank(event.getSourceRef(), "");
        int index = module.indexOf('.');
        if (index > 0) {
            module = module.substring(0, index);
        }
        String businessKey = String.join("|",
                defaultIfBlank(sourceSection, ""),
                defaultIfBlank(event.getEventType(), ""),
                defaultIfBlank(event.getEventSubtype(), ""),
                event.getEventTime() == null ? "" : DateTimeUtils.format(event.getEventTime()),
                defaultIfBlank(event.getSite(), ""),
                defaultIfBlank(event.getTitle(), ""),
                defaultIfBlank(event.getContent(), ""));
        return String.join("|",
                defaultIfBlank(event.getReqno(), ""),
                event.getDataDate() == null ? "" : event.getDataDate().toString(),
                defaultIfBlank(event.getSourceType(), ""),
                module,
                shortHash(businessKey));
    }

    private String shortHash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(defaultIfBlank(raw, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash event business key", e);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize normalized event payload", e);
        }
    }

    private String normalizeAlias(String rawValue, Map<String, String> aliases) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        return aliases.getOrDefault(normalized, normalized);
    }

    private String normalizeForSearch(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return "";
        }
        return rawValue
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("\\\"", "\"")
                .replaceAll("\\s+", "");
    }

    private boolean containsAny(String rawValue, String... patterns) {
        if (!StringUtils.hasText(rawValue)) {
            return false;
        }
        for (String pattern : patterns) {
            if (StringUtils.hasText(pattern) && rawValue.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
