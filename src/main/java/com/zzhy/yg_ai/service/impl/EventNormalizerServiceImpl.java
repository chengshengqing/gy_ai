package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionAbnormalFlag;
import com.zzhy.yg_ai.domain.enums.InfectionBodySite;
import com.zzhy.yg_ai.domain.enums.InfectionCertainty;
import com.zzhy.yg_ai.domain.enums.InfectionClinicalMeaning;
import com.zzhy.yg_ai.domain.enums.InfectionEvidenceRole;
import com.zzhy.yg_ai.domain.enums.InfectionEvidenceTier;
import com.zzhy.yg_ai.domain.enums.InfectionEventStatus;
import com.zzhy.yg_ai.domain.enums.InfectionEventSubtype;
import com.zzhy.yg_ai.domain.enums.InfectionEventType;
import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.enums.InfectionPolarity;
import com.zzhy.yg_ai.domain.enums.InfectionSeverity;
import com.zzhy.yg_ai.domain.enums.InfectionSourceSection;
import com.zzhy.yg_ai.domain.schema.InfectionEventSchema;
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
import java.util.List;
import java.util.Locale;
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
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String JSON_NULL_LITERAL = "null";
    private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.60");
    private static final String SUBTYPE_CULTURE_ORDERED = InfectionEventSubtype.CULTURE_ORDERED.code();
    private static final String SUBTYPE_CONTAMINATION_TOKEN = "contamination";
    private static final String SUBTYPE_COLONIZATION_TOKEN = "colonization";
    private static final String[] OPERATION_SUPPORT_HINT_KEYWORDS = {"感染", "脓", "发热", "炎"};
    private static final String[] COMPLETED_CULTURE_RESULT_KEYWORDS = {"阳性", "阴性", "异常", "正常"};

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
        if (!root.isObject()) {
            throw new IllegalStateException("EventNormalizer extractor output must be a JSON object");
        }
        String status = normalizeStatus(root.path("status").asText(""));
        if (!StringUtils.hasText(status)) {
            throw new IllegalStateException("EventNormalizer response status is invalid");
        }
        if (STATUS_SKIPPED.equals(status)) {
            if (root.path("events").isArray() && root.path("events").isEmpty()) {
                return List.of();
            }
            throw new IllegalStateException("EventNormalizer skipped response must contain empty events");
        }
        BigDecimal responseConfidence = parseRootConfidence(root.path("confidence"));
        if (responseConfidence == null) {
            throw new IllegalStateException("EventNormalizer response confidence is invalid");
        }
        JsonNode eventsNode = root.path("events");
        if (!eventsNode.isArray()) {
            throw new IllegalStateException("EventNormalizer response events must be an array");
        }
        if (eventsNode.isEmpty()) {
            throw new IllegalStateException("EventNormalizer success response must contain events");
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
        if (result.isEmpty()) {
            throw new IllegalStateException("EventNormalizer rejected all extracted events");
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
        JsonNode sourceSectionNode = source.get("source_section");
        String sourceSection = normalizeSourceSection(sourceSectionNode);
        if (EvidenceBlockType.STRUCTURED_FACT.equals(block.blockType()) && !StringUtils.hasText(sourceSection)) {
            return null;
        }
        if (!EvidenceBlockType.STRUCTURED_FACT.equals(block.blockType()) && hasExplicitSourceSectionValue(sourceSectionNode)) {
            return null;
        }

        Boolean infectionRelated = parseBoolean(source.get("infection_related"));
        Boolean negationFlag = parseBoolean(source.get("negation_flag"));
        Boolean uncertaintyFlag = parseBoolean(source.get("uncertainty_flag"));
        if (infectionRelated == null || negationFlag == null || uncertaintyFlag == null) {
            return null;
        }
        if (InfectionEvidenceRole.BACKGROUND.code().equals(evidenceRole) && Boolean.TRUE.equals(infectionRelated)) {
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
        if (!validateClinicalConsistency(infectionRelated, negationFlag, uncertaintyFlag, evidenceRole, clinicalMeaning, eventSubtype)) {
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
            throw new IllegalStateException("EventNormalizer extractor output is blank");
        }
        try {
            return objectMapper.readTree(extractorOutputJson);
        } catch (Exception e) {
            throw new IllegalStateException("EventNormalizer failed to parse extractor output JSON", e);
        }
    }

    private String normalizeStatus(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return null;
        }
        String normalized = rawStatus.trim().toLowerCase(Locale.ROOT);
        return (STATUS_SUCCESS.equals(normalized) || STATUS_SKIPPED.equals(normalized)) ? normalized : null;
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
        if (!StringUtils.hasText(rawType)) {
            return null;
        }
        try {
            return InfectionEventType.fromCode(rawType.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeSubtype(String rawSubtype) {
        if (!StringUtils.hasText(rawSubtype)) {
            return null;
        }
        try {
            return InfectionEventSubtype.fromCode(rawSubtype.trim().toLowerCase(Locale.ROOT)).code();
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
        if (!StringUtils.hasText(rawMeaning)) {
            return null;
        }
        try {
            return InfectionClinicalMeaning.fromCode(rawMeaning.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeEvidenceTier(String rawTier) {
        if (!StringUtils.hasText(rawTier)) {
            return null;
        }
        try {
            return InfectionEvidenceTier.fromCode(rawTier.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeEvidenceRole(String rawRole) {
        if (!StringUtils.hasText(rawRole)) {
            return null;
        }
        try {
            return InfectionEvidenceRole.fromCode(rawRole.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeSourceSection(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String rawValue = node.asText("");
        if (!StringUtils.hasText(rawValue) || JSON_NULL_LITERAL.equalsIgnoreCase(rawValue.trim())) {
            return null;
        }
        try {
            return InfectionSourceSection.fromCode(rawValue.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean hasExplicitSourceSectionValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        String rawValue = node.asText("");
        return StringUtils.hasText(rawValue) && !JSON_NULL_LITERAL.equalsIgnoreCase(rawValue.trim());
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
        Set<String> allowedTypes = InfectionEventSchema.allowedEventTypesForSection(sourceSection);
        if (allowedTypes == null || !allowedTypes.contains(eventType)) {
            return false;
        }
        if (InfectionSourceSection.VITAL_SIGNS.code().equals(sourceSection)
                && InfectionEvidenceTier.HARD.code().equals(evidenceTier)) {
            return false;
        }
        if (InfectionSourceSection.DIAGNOSIS.code().equals(sourceSection)
                && InfectionEvidenceTier.HARD.code().equals(evidenceTier)) {
            return false;
        }
        if (InfectionSourceSection.TRANSFER.code().equals(sourceSection)
                && InfectionEvidenceRole.SUPPORT.code().equals(evidenceRole)) {
            return false;
        }
        if (InfectionSourceSection.OPERATION.code().equals(sourceSection)
                && InfectionEvidenceRole.SUPPORT.code().equals(evidenceRole)
                && !containsAny(sourceText, OPERATION_SUPPORT_HINT_KEYWORDS)) {
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

    private boolean validateClinicalConsistency(Boolean infectionRelated,
                                                Boolean negationFlag,
                                                Boolean uncertaintyFlag,
                                                String evidenceRole,
                                                String clinicalMeaning,
                                                String eventSubtype) {
        if (InfectionEvidenceRole.AGAINST.code().equals(evidenceRole) && Boolean.FALSE.equals(negationFlag)
                && !InfectionClinicalMeaning.INFECTION_AGAINST.code().equals(clinicalMeaning)
                && !isContaminationOrColonizationSubtype(eventSubtype)) {
            return false;
        }
        if (InfectionEvidenceRole.RISK_ONLY.code().equals(evidenceRole) && Boolean.TRUE.equals(uncertaintyFlag)
                && InfectionClinicalMeaning.INFECTION_SUPPORT.code().equals(clinicalMeaning)) {
            return false;
        }
        return true;
    }

    private boolean validateCompletedResultRule(ObjectNode source, String eventSubtype, String sourceSection) {
        if (!SUBTYPE_CULTURE_ORDERED.equals(eventSubtype)) {
            return true;
        }
        if (!InfectionSourceSection.LAB_RESULTS.code().equals(sourceSection)) {
            return true;
        }
        String sourceText = defaultIfBlank(source.path("source_text").asText(""), "");
        return !containsAny(sourceText, COMPLETED_CULTURE_RESULT_KEYWORDS);
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
        if (InfectionEvidenceRole.AGAINST.code().equals(evidenceRole) || negationFlag
                || InfectionClinicalMeaning.INFECTION_AGAINST.code().equals(clinicalMeaning)) {
            return InfectionPolarity.NEGATIVE.code();
        }
        if (uncertaintyFlag || InfectionClinicalMeaning.INFECTION_UNCERTAIN.code().equals(clinicalMeaning)) {
            return InfectionPolarity.UNCERTAIN.code();
        }
        if (InfectionEvidenceRole.SUPPORT.code().equals(evidenceRole)
                || InfectionClinicalMeaning.INFECTION_SUPPORT.code().equals(clinicalMeaning)) {
            return InfectionPolarity.POSITIVE.code();
        }
        return InfectionPolarity.NEUTRAL.code();
    }

    private String inferCertainty(String evidenceRole,
                                  boolean uncertaintyFlag,
                                  EvidenceBlockType blockType,
                                  BigDecimal confidence) {
        if (InfectionEvidenceRole.RISK_ONLY.code().equals(evidenceRole)) {
            return InfectionCertainty.RISK_ONLY.code();
        }
        if (uncertaintyFlag) {
            return InfectionCertainty.POSSIBLE.code();
        }
        if (confidence != null && confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0
                && !EvidenceBlockType.STRUCTURED_FACT.equals(blockType)) {
            return InfectionCertainty.POSSIBLE.code();
        }
        return InfectionCertainty.CONFIRMED.code();
    }

    private String resolveEventCategory(EvidenceBlockType blockType) {
        return InfectionEventSchema.resolveEventCategory(blockType);
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
        try {
            return InfectionAbnormalFlag.fromCode(normalized).code();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isContaminationOrColonizationSubtype(String eventSubtype) {
        String normalizedSubtype = defaultIfBlank(eventSubtype, "");
        return normalizedSubtype.contains(SUBTYPE_CONTAMINATION_TOKEN)
                || normalizedSubtype.contains(SUBTYPE_COLONIZATION_TOKEN);
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
