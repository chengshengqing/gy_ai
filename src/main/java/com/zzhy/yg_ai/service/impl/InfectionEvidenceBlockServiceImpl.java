package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.StructuredFactRefinementService;
import com.zzhy.yg_ai.service.evidence.ClinicalTextBlockBuilder;
import com.zzhy.yg_ai.service.evidence.MidSemanticBlockBuilder;
import com.zzhy.yg_ai.service.evidence.StructuredFactBlockBuilder;
import com.zzhy.yg_ai.service.evidence.TimelineContextBlockBuilder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfectionEvidenceBlockServiceImpl implements InfectionEvidenceBlockService {

    private final StructuredFactBlockBuilder structuredFactBlockBuilder;
    private final ClinicalTextBlockBuilder clinicalTextBlockBuilder;
    private final MidSemanticBlockBuilder midSemanticBlockBuilder;
    private final TimelineContextBlockBuilder timelineContextBlockBuilder;
    private final StructuredFactRefinementService structuredFactRefinementService;
    private final ObjectMapper objectMapper;

    @Override
    public EvidenceBlockBuildResult buildBlocks(PatientRawDataEntity rawData, String timelineWindowJson) {
        if (rawData == null) {
            return new EvidenceBlockBuildResult(null, null, null);
        }
        List<EvidenceBlock> timelineBlocks = filterTimelineContextBlocks(
                timelineContextBlockBuilder.build(rawData, timelineWindowJson)
        );
        EvidenceBlock timelineBlock = timelineBlocks.isEmpty() ? null : timelineBlocks.get(0);
        JsonNode midSemanticData = buildMidSemanticData(rawData, timelineWindowJson);
        List<EvidenceBlock> structuredBlocks = addStructuredMidSemanticContext(
                buildStructuredBlocks(rawData, timelineWindowJson, timelineBlock),
                midSemanticData
        );
        List<EvidenceBlock> clinicalTextBlocks = addClinicalMidSemanticContext(
                clinicalTextBlockBuilder.build(rawData, timelineWindowJson),
                midSemanticData
        );
        return new EvidenceBlockBuildResult(
                structuredBlocks,
                clinicalTextBlocks,
                timelineBlocks
        );
    }

    private JsonNode buildMidSemanticData(PatientRawDataEntity rawData, String timelineWindowJson) {
        return midSemanticBlockBuilder.buildContextData(rawData, timelineWindowJson);
    }

    private List<EvidenceBlock> addStructuredMidSemanticContext(List<EvidenceBlock> blocks, JsonNode midSemanticData) {
        JsonNode context = midSemanticData == null ? null : midSemanticData.path("structured_context");
        if (!hasMeaningfulContent(context)) {
            return blocks == null ? List.of() : List.copyOf(blocks);
        }
        return addMidSemanticContext(blocks, context, "structured_fact_mid_semantic_context");
    }

    private List<EvidenceBlock> addClinicalMidSemanticContext(List<EvidenceBlock> blocks, JsonNode midSemanticData) {
        if (blocks == null || blocks.isEmpty() || midSemanticData == null || !midSemanticData.isObject()) {
            return blocks == null ? List.of() : List.copyOf(blocks);
        }
        List<EvidenceBlock> result = new ArrayList<>();
        for (EvidenceBlock block : blocks) {
            ObjectNode context = buildClinicalMidSemanticContext(block, midSemanticData.path("clinical_context").path("note_semantics"));
            if (hasMeaningfulContent(context)) {
                result.add(addMidSemanticContext(block, context, "clinical_text_mid_semantic_context"));
            } else {
                result.add(block);
            }
        }
        return List.copyOf(result);
    }

    private ObjectNode buildClinicalMidSemanticContext(EvidenceBlock block, JsonNode noteSemantics) {
        ObjectNode context = objectMapper.createObjectNode();
        if (block == null || !noteSemantics.isArray()) {
            return context;
        }
        JsonNode payload = readPayload(block, "clinical_text_payload");
        Set<String> noteRefs = textSet(payload == null ? null : payload.path("note_refs"));
        Set<String> noteTypes = textSet(payload == null ? null : payload.path("note_types"));
        Set<String> noteTimes = textSet(payload == null ? null : payload.path("note_times"));
        ArrayNode notes = objectMapper.createArrayNode();
        for (JsonNode note : noteSemantics) {
            if (!matchesClinicalNote(note, noteRefs, noteTypes, noteTimes)) {
                continue;
            }
            JsonNode pruned = pruneEmptyNodes(note);
            if (hasMeaningfulContent(pruned)) {
                notes.add(pruned);
            }
        }
        setIfMeaningful(context, "note_semantics", notes);
        return context;
    }

    private boolean matchesClinicalNote(JsonNode note, Set<String> noteRefs, Set<String> noteTypes, Set<String> noteTimes) {
        if (noteRefs.isEmpty() && noteTypes.isEmpty() && noteTimes.isEmpty()) {
            return true;
        }
        String ref = note.path("ref").asText("");
        String normalizedRef = normalizeRef(ref);
        for (String noteRef : noteRefs) {
            if (normalizedRef.equals(normalizeRef(noteRef))) {
                return true;
            }
        }
        int splitAt = ref.indexOf('@');
        if (splitAt <= 0 || splitAt >= ref.length() - 1) {
            return false;
        }
        String noteType = ref.substring(0, splitAt).trim();
        String noteTime = ref.substring(splitAt + 1).trim();
        return containsNormalized(noteTypes, noteType) && containsNormalized(noteTimes, noteTime);
    }

    private boolean containsNormalized(Set<String> values, String expected) {
        String normalizedExpected = normalizeRef(expected);
        if (!StringUtils.hasText(normalizedExpected)) {
            return false;
        }
        for (String value : values) {
            if (normalizedExpected.equals(normalizeRef(value))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeRef(String value) {
        return StringUtils.hasText(value) ? value.trim().replaceAll("[\\s_]+", "") : "";
    }

    private List<EvidenceBlock> addMidSemanticContext(List<EvidenceBlock> blocks, JsonNode context, String sourceName) {
        if (blocks == null || blocks.isEmpty() || !hasMeaningfulContent(context)) {
            return blocks == null ? List.of() : List.copyOf(blocks);
        }
        List<EvidenceBlock> result = new ArrayList<>();
        for (EvidenceBlock block : blocks) {
            result.add(addMidSemanticContext(block, context, sourceName));
        }
        return List.copyOf(result);
    }

    private EvidenceBlock addMidSemanticContext(EvidenceBlock block, JsonNode context, String sourceName) {
        JsonNode payload = readPayload(block, sourceName);
        if (!(payload instanceof ObjectNode payloadObject)) {
            return block;
        }
        ObjectNode copiedPayload = payloadObject.deepCopy();
        copiedPayload.set("mid_semantic_context", context.deepCopy());
        return copyWithPayload(block, copiedPayload);
    }

    private List<EvidenceBlock> filterTimelineContextBlocks(List<EvidenceBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<EvidenceBlock> result = new ArrayList<>();
        for (EvidenceBlock block : blocks) {
            EvidenceBlock filteredBlock = filterTimelineContextBlock(block);
            if (filteredBlock != null) {
                result.add(filteredBlock);
            }
        }
        return List.copyOf(result);
    }

    private EvidenceBlock filterTimelineContextBlock(EvidenceBlock block) {
        if (block == null || block.blockType() != EvidenceBlockType.TIMELINE_CONTEXT) {
            return block;
        }
        JsonNode payload = readPayload(block, "timeline_context_payload");
        if (payload == null || !payload.isObject()) {
            return block;
        }
        JsonNode changes = payload.path("data").path("changes");
        return hasMeaningfulContent(changes) ? block : null;
    }

    private List<EvidenceBlock> buildStructuredBlocks(PatientRawDataEntity rawData,
                                                      String timelineWindowJson,
                                                      EvidenceBlock timelineBlock) {
        List<EvidenceBlock> result = new ArrayList<>();
        for (EvidenceBlock block : structuredFactBlockBuilder.build(rawData, timelineWindowJson)) {
            EvidenceBlock compactedBlock = compactStructuredFactBlock(block);
            if (compactedBlock == null) {
                continue;
            }
            EvidenceBlock refinedBlock = structuredFactRefinementService.refine(compactedBlock, timelineBlock);
            EvidenceBlock compactedRefinedBlock = compactStructuredFactBlock(refinedBlock);
            if (compactedRefinedBlock != null) {
                result.add(compactedRefinedBlock);
            }
        }
        return List.copyOf(result);
    }

    private EvidenceBlock compactStructuredFactBlock(EvidenceBlock block) {
        if (block == null || block.blockType() != EvidenceBlockType.STRUCTURED_FACT) {
            return block;
        }
        JsonNode payload = readPayload(block, "structured_fact_block_payload");
        if (!(payload instanceof ObjectNode payloadObject)) {
            return block;
        }
        ObjectNode compactedPayload = payloadObject.deepCopy();
        JsonNode dataNode = compactedPayload.path("data");
        if (!(dataNode instanceof ObjectNode dataObject)) {
            return block;
        }

        pruneDataSections(dataObject);
        if (!hasMeaningfulContent(dataObject)) {
            return null;
        }
        if (payloadObject.equals(compactedPayload)) {
            return block;
        }
        return copyWithPayload(block, compactedPayload);
    }

    private void pruneDataSections(ObjectNode dataObject) {
        Map<String, JsonNode> compactedSections = new LinkedHashMap<>();
        List<String> emptySectionNames = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = dataObject.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode compactedSection = pruneEmptyNodes(field.getValue());
            if (hasMeaningfulContent(compactedSection)) {
                compactedSections.put(field.getKey(), compactedSection);
            } else {
                emptySectionNames.add(field.getKey());
            }
        }
        compactedSections.forEach(dataObject::set);
        emptySectionNames.forEach(dataObject::remove);
    }

    private JsonNode pruneEmptyNodes(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return NullNode.getInstance();
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode compactedChild = pruneEmptyNodes(field.getValue());
                if (hasMeaningfulContent(compactedChild)) {
                    result.set(field.getKey(), compactedChild);
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                JsonNode compactedItem = pruneEmptyNodes(item);
                if (hasMeaningfulContent(compactedItem)) {
                    result.add(compactedItem);
                }
            }
            return result;
        }
        return node.deepCopy();
    }

    private Set<String> textSet(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            String value = item.asText("");
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private void setIfMeaningful(ObjectNode target, String fieldName, JsonNode value) {
        if (hasMeaningfulContent(value)) {
            target.set(fieldName, value);
        }
    }

    private boolean hasMeaningfulContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return StringUtils.hasText(node.asText());
        }
        if (node.isNumber() || node.isBoolean()) {
            return true;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (hasMeaningfulContent(item)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                if (hasMeaningfulContent(values.next())) {
                    return true;
                }
            }
            return false;
        }
        return !node.isEmpty();
    }

    private JsonNode readPayload(EvidenceBlock block, String sourceName) {
        try {
            return objectMapper.readTree(block.payloadJson());
        } catch (Exception e) {
            log.warn("EvidenceBlock payload 解析失败，sourceName={}, blockKey={}, rawDataId={}",
                    sourceName, block.blockKey(), block.rawDataId(), e);
            return null;
        }
    }

    private EvidenceBlock copyWithPayload(EvidenceBlock block, JsonNode payload) {
        try {
            return new EvidenceBlock(
                    block.blockKey(),
                    block.reqno(),
                    block.rawDataId(),
                    block.dataDate(),
                    block.blockType(),
                    block.sourceType(),
                    block.sourceRef(),
                    block.title(),
                    objectMapper.writeValueAsString(payload),
                    block.contextOnly()
            );
        } catch (Exception e) {
            throw new IllegalStateException("EvidenceBlock payload 序列化失败，blockKey=" + block.blockKey(), e);
        }
    }
}
