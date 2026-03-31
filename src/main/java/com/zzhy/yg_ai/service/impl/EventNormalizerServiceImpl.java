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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventNormalizerServiceImpl implements EventNormalizerService {

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = DateTimeUtils.DATE_TIME_PARSE_FORMATTERS;

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );

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
        JsonNode eventsNode = root.path("events");
        if (!eventsNode.isArray() || eventsNode.isEmpty()) {
            return List.of();
        }

        List<NormalizedInfectionEvent> result = new ArrayList<>();
        for (JsonNode eventNode : eventsNode) {
            if (!eventNode.isObject()) {
                continue;
            }
            NormalizedInfectionEvent event = normalizeSingle(block, (ObjectNode) eventNode, extractorType, promptVersion,
                    modelName, confidence);
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
                                                     BigDecimal confidence) {
        String eventSubtype = normalizeSubtype(source.path("event_subtype").asText(""));
        String eventType = normalizeEventType(source.path("event_type").asText(""), eventSubtype, block);
        if (!StringUtils.hasText(eventType)) {
            return null;
        }

        String title = firstNonBlank(source.path("event_name").asText(""), eventSubtype, eventType);
        String sourceText = source.path("source_text").asText("");
        String content = firstNonBlank(sourceText, title);
        LocalDateTime eventTime = normalizeEventTime(source.path("event_time").asText(""), block.dataDate());
        String clinicalMeaning = normalizeClinicalMeaning(source.path("clinical_meaning").asText(""));
        String site = normalizeSite(source.path("body_site").asText(""));
        String polarity = inferPolarity(source, eventSubtype, clinicalMeaning);
        String certainty = inferCertainty(source, clinicalMeaning, block.blockType(), confidence);
        String severity = normalizeSeverity(null);

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
        event.setSite(site);
        event.setPolarity(polarity);
        event.setCertainty(certainty);
        event.setSeverity(severity);
        event.setIsHardFact(EvidenceBlockType.STRUCTURED_FACT.equals(block.blockType()));
        event.setIsActive(Boolean.TRUE);
        event.setTitle(title);
        event.setContent(content);
        event.setExtractorType(extractorType == null ? null : extractorType.code());
        event.setPromptVersion(promptVersion);
        event.setModelName(modelName);
        event.setConfidence(confidence);
        event.setStatus(InfectionEventStatus.ACTIVE.code());
        event.setEvidenceJson(buildEvidenceJson(block, source, sourceText));
        event.setAttributesJson(buildAttributesJson(source, clinicalMeaning));
        event.setEventKey(buildEventKey(event));
        return event;
    }

    private JsonNode parseRoot(String extractorOutputJson) {
        if (!StringUtils.hasText(extractorOutputJson)) {
            ObjectNode empty = objectMapper.createObjectNode();
            empty.set("events", objectMapper.createArrayNode());
            return empty;
        }
        try {
            return objectMapper.readTree(extractorOutputJson);
        } catch (Exception e) {
            log.warn("EventNormalizer 解析 extractor 输出失败", e);
            ObjectNode fallback = objectMapper.createObjectNode();
            ArrayNode events = objectMapper.createArrayNode();
            fallback.set("events", events);
            return fallback;
        }
    }

    private String normalizeEventType(String rawType, String eventSubtype, EvidenceBlock block) {
        if (StringUtils.hasText(rawType)) {
            try {
                return InfectionEventType.fromCode(rawType.trim().toLowerCase(Locale.ROOT)).code();
            } catch (IllegalArgumentException ignore) {
                // fall through
            }
        }
        if (StringUtils.hasText(eventSubtype)) {
            try {
                return InfectionEventSubtype.fromCode(eventSubtype).eventType().code();
            } catch (IllegalArgumentException ignore) {
                // fall through
            }
        }
        return switch (block.blockType()) {
            case STRUCTURED_FACT -> inferFactTypeBySourceRef(block.sourceRef());
            case CLINICAL_TEXT -> InfectionEventType.NOTE.code();
            case MID_SEMANTIC -> InfectionEventType.PROBLEM.code();
            case TIMELINE_CONTEXT -> null;
        };
    }

    private String inferFactTypeBySourceRef(String sourceRef) {
        String normalized = defaultIfBlank(sourceRef, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("vital")) {
            return InfectionEventType.VITAL_SIGN.code();
        }
        if (normalized.contains("lab")) {
            return InfectionEventType.LAB_RESULT.code();
        }
        if (normalized.contains("image") || normalized.contains("imaging")) {
            return InfectionEventType.IMAGING.code();
        }
        if (normalized.contains("order") || normalized.contains("medicine")) {
            return InfectionEventType.ORDER.code();
        }
        if (normalized.contains("device")) {
            return InfectionEventType.DEVICE.code();
        }
        if (normalized.contains("operation") || normalized.contains("procedure")) {
            return InfectionEventType.PROCEDURE.code();
        }
        if (normalized.contains("micro")) {
            return InfectionEventType.MICROBIOLOGY.code();
        }
        return InfectionEventType.DIAGNOSIS.code();
    }

    private String normalizeSubtype(String rawSubtype) {
        if (!StringUtils.hasText(rawSubtype)) {
            return null;
        }
        try {
            return InfectionEventSubtype.fromCode(rawSubtype.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return rawSubtype.trim().toLowerCase(Locale.ROOT);
        }
    }

    private LocalDateTime normalizeEventTime(String rawEventTime, LocalDate dataDate) {
        if (StringUtils.hasText(rawEventTime)) {
            String value = rawEventTime.trim();
            for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
                try {
                    return DateTimeUtils.truncateToMillis(LocalDateTime.parse(value, formatter));
                } catch (DateTimeParseException ignore) {
                    // try next
                }
            }
            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    return LocalDate.parse(value, formatter).atStartOfDay();
                } catch (DateTimeParseException ignore) {
                    // try next
                }
            }
        }
        return dataDate == null ? null : DateTimeUtils.truncateToMillis(LocalDateTime.of(dataDate, LocalTime.MIN));
    }

    private String normalizeSite(String rawSite) {
        if (!StringUtils.hasText(rawSite)) {
            return InfectionBodySite.UNKNOWN.code();
        }
        try {
            return InfectionBodySite.fromCode(rawSite.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return InfectionBodySite.UNKNOWN.code();
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

    private String normalizeSeverity(String rawSeverity) {
        if (!StringUtils.hasText(rawSeverity)) {
            return InfectionSeverity.UNCLEAR.code();
        }
        try {
            return InfectionSeverity.fromCode(rawSeverity.trim().toLowerCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            return InfectionSeverity.UNCLEAR.code();
        }
    }

    private String inferPolarity(ObjectNode source, String eventSubtype, String clinicalMeaning) {
        boolean negationFlag = source.path("negation_flag").asBoolean(false);
        boolean uncertaintyFlag = source.path("uncertainty_flag").asBoolean(false);
        boolean infectionRelated = source.path("infection_related").asBoolean(false);

        if (negationFlag || InfectionClinicalMeaning.INFECTION_AGAINST.code().equals(clinicalMeaning)) {
            return InfectionPolarity.NEGATIVE.code();
        }
        if (InfectionClinicalMeaning.DEVICE_EXPOSURE.code().equals(clinicalMeaning)
                || InfectionClinicalMeaning.PROCEDURE_EXPOSURE.code().equals(clinicalMeaning)
                || defaultIfBlank(eventSubtype, "").contains("colonization")
                || defaultIfBlank(eventSubtype, "").contains("contamination")) {
            return InfectionPolarity.NEUTRAL.code();
        }
        if (infectionRelated || InfectionClinicalMeaning.INFECTION_SUPPORT.code().equals(clinicalMeaning)) {
            return InfectionPolarity.POSITIVE.code();
        }
        if (uncertaintyFlag || InfectionClinicalMeaning.INFECTION_UNCERTAIN.code().equals(clinicalMeaning)) {
            return InfectionPolarity.UNCERTAIN.code();
        }
        return InfectionPolarity.NEUTRAL.code();
    }

    private String inferCertainty(ObjectNode source,
                                  String clinicalMeaning,
                                  EvidenceBlockType blockType,
                                  BigDecimal confidence) {
        boolean uncertaintyFlag = source.path("uncertainty_flag").asBoolean(false);
        if (uncertaintyFlag) {
            return InfectionCertainty.POSSIBLE.code();
        }
        if (confidence != null && confidence.compareTo(new BigDecimal("0.60")) < 0
                && !EvidenceBlockType.STRUCTURED_FACT.equals(blockType)) {
            return InfectionCertainty.POSSIBLE.code();
        }
        if (InfectionClinicalMeaning.DEVICE_EXPOSURE.code().equals(clinicalMeaning)
                || InfectionClinicalMeaning.PROCEDURE_EXPOSURE.code().equals(clinicalMeaning)
                || InfectionClinicalMeaning.SCREENING.code().equals(clinicalMeaning)) {
            return InfectionCertainty.RISK_ONLY.code();
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

    private String buildEvidenceJson(EvidenceBlock block, ObjectNode source, String sourceText) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("block_key", block.blockKey());
        evidence.put("block_type", block.blockType().name());
        evidence.put("source_ref", block.sourceRef());
        evidence.put("source_text", sourceText);
        evidence.set("raw_event", source.deepCopy());
        return writeJson(evidence);
    }

    private String buildAttributesJson(ObjectNode source, String clinicalMeaning) {
        ObjectNode attributes = objectMapper.createObjectNode();
        copyIfPresent(source, attributes, "event_value");
        copyIfPresent(source, attributes, "event_unit");
        copyIfPresent(source, attributes, "abnormal_flag");
        copyIfPresent(source, attributes, "infection_related");
        copyIfPresent(source, attributes, "negation_flag");
        copyIfPresent(source, attributes, "uncertainty_flag");
        if (StringUtils.hasText(clinicalMeaning)) {
            attributes.put("clinical_meaning", clinicalMeaning);
        }
        return writeJson(attributes);
    }

    private void copyIfPresent(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode node = source.get(fieldName);
        if (node != null && !node.isNull()) {
            target.set(fieldName, node.deepCopy());
        }
    }

    private String buildEventKey(NormalizedInfectionEvent event) {
        String module = defaultIfBlank(event.getSourceRef(), "");
        int index = module.indexOf('.');
        if (index > 0) {
            module = module.substring(0, index);
        }
        String businessKey = String.join("|",
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
