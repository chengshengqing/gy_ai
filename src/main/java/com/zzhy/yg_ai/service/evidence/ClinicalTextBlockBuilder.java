package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.gateway.AiGateway;
import com.zzhy.yg_ai.ai.prompt.WarningPromptCatalog;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class ClinicalTextBlockBuilder extends AbstractEvidenceBlockBuilder {

    private static final int MAX_NOTE_TEXT_LENGTH = 5000;
    private static final int MAX_CANDIDATE_ITEMS = 16;
    private static final String CLINICAL_TEXT_CANDIDATE_SELECTOR_NODE = "CLINICAL_TEXT_CANDIDATE_SELECTOR";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_SKIPPED = "skipped";

    private final AiGateway aiGateway;

    public ClinicalTextBlockBuilder(ObjectMapper objectMapper, AiGateway aiGateway) {
        super(objectMapper);
        this.aiGateway = aiGateway;
    }

    @Override
    public List<EvidenceBlock> build(PatientRawDataEntity rawData, String timelineWindowJson) {
        ObjectNode filteredRoot = parseObject(rawData == null ? null : rawData.getFilterDataJson(), "filter_data_json",
                rawData == null ? null : rawData.getId());
        ArrayNode notes = extractNotes(filteredRoot);
        if (!hasMeaningfulContent(notes)) {
            ObjectNode rawRoot = parseObject(rawData == null ? null : rawData.getDataJson(), "data_json",
                    rawData == null ? null : rawData.getId());
            notes = extractNotes(rawRoot);
        }
        if (!hasMeaningfulContent(notes)) {
            return List.of();
        }

        List<EvidenceBlock> mergedBlocks = buildMergedBlocks(rawData, notes);
        if (mergedBlocks.isEmpty()) {
            return List.of();
        }
        return List.copyOf(selectCandidateBlocks(rawData, mergedBlocks));
    }

    private ArrayNode extractNotes(ObjectNode root) {
        JsonNode notes = root.path("pat_illnessCourse");
        if (notes.isArray()) {
            return (ArrayNode) notes;
        }
        return objectMapper.createArrayNode();
    }

    private String buildNoteRef(String noteId, String noteType, String noteTime, int index) {
        if (StringUtils.hasText(noteId)) {
            return noteId.trim();
        }
        if (StringUtils.hasText(noteType) || StringUtils.hasText(noteTime)) {
            return (value(noteType) + "@" + value(noteTime)).replaceAll("\\s+", "_");
        }
        return "note_" + index;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = node.path(fieldName).asText("");
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private List<EvidenceBlock> buildMergedBlocks(PatientRawDataEntity rawData, ArrayNode notes) {
        List<EvidenceBlock> result = new ArrayList<>();
        List<NoteItem> buffer = new ArrayList<>();
        int totalLength = 0;
        int index = 0;
        for (JsonNode note : notes) {
            NoteItem item = toNoteItem(note, index);
            index++;
            if (item == null) {
                continue;
            }
            int candidateLength = buffer.isEmpty()
                    ? item.noteText().length()
                    : totalLength + 2 + item.noteText().length();
            if (!buffer.isEmpty() && candidateLength > MAX_NOTE_TEXT_LENGTH) {
                result.add(toBlock(rawData, buffer));
                buffer = new ArrayList<>();
                totalLength = 0;
            }
            buffer.add(item);
            totalLength = buffer.size() == 1 ? item.noteText().length() : totalLength + 2 + item.noteText().length();
        }
        if (!buffer.isEmpty()) {
            result.add(toBlock(rawData, buffer));
        }
        return result;
    }

    private List<EvidenceBlock> selectCandidateBlocks(PatientRawDataEntity rawData, List<EvidenceBlock> blocks) {
        List<EvidenceBlock> result = new ArrayList<>();
        for (EvidenceBlock block : blocks) {
            EvidenceBlock selectedBlock = selectCandidateBlock(block);
            if (selectedBlock != null) {
                result.add(selectedBlock);
            }
        }
        return mergeCandidateBlocks(rawData, result);
    }

    private EvidenceBlock selectCandidateBlock(EvidenceBlock block) {
        ObjectNode payload = parseObject(block.payloadJson(), "clinical_text_block_payload", block.rawDataId());
        Map<String, NotePayload> notesByRef = extractNotePayloads(payload.path("note_items"));
        if (notesByRef.isEmpty()) {
            return stripSelectorOnlyFields(block);
        }
        try {
            String inputPayload = buildCandidateSelectorInput(block, notesByRef.values());
            String rawOutput = aiGateway.callSystem(
                    PipelineStage.EVENT_EXTRACT,
                    CLINICAL_TEXT_CANDIDATE_SELECTOR_NODE,
                    WarningPromptCatalog.buildClinicalTextCandidateSelectorPrompt(),
                    inputPayload
            );
            CandidateSelection selection = parseCandidateSelection(rawOutput, notesByRef);
            if (selection.items().isEmpty()) {
                return null;
            }
            return buildCandidateBlock(block, selection.items());
        } catch (Exception e) {
            log.warn("ClinicalText candidate selection failed, blockKey={}, reqno={}, rawDataId={}, sourceRef={}",
                    block.blockKey(), block.reqno(), block.rawDataId(), block.sourceRef(), e);
            return stripSelectorOnlyFields(block);
        }
    }

    private String buildCandidateSelectorInput(EvidenceBlock block, Iterable<NotePayload> noteItems) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("reqno", block.reqno());
        input.put("rawDataId", block.rawDataId());
        input.put("dataDate", block.dataDate() == null ? null : block.dataDate().toString());
        input.put("blockType", block.blockType().name());
        input.put("sourceRef", block.sourceRef());
        input.put("title", block.title());
        ArrayNode notes = objectMapper.createArrayNode();
        for (NotePayload note : noteItems) {
            ObjectNode item = notes.addObject();
            item.put("note_ref", note.noteRef());
            item.put("note_id", note.noteId());
            item.put("note_type", note.noteType());
            item.put("note_time", note.noteTime());
            item.put("note_text", note.noteText());
        }
        input.set("note_items", notes);
        return toJson(input);
    }

    private CandidateSelection parseCandidateSelection(String rawOutput, Map<String, NotePayload> notesByRef) {
        if (!StringUtils.hasText(rawOutput)) {
            throw new IllegalStateException("ClinicalText candidate selector output is blank");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawOutput);
        } catch (Exception e) {
            throw new IllegalStateException("ClinicalText candidate selector output is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("ClinicalText candidate selector output must be a JSON object");
        }
        String status = root.path("status").asText("").trim().toLowerCase(Locale.ROOT);
        if (!STATUS_SUCCESS.equals(status) && !STATUS_SKIPPED.equals(status)) {
            throw new IllegalStateException("ClinicalText candidate selector status is invalid: " + status);
        }
        JsonNode candidateItemsNode = root.path("candidate_items");
        if (!candidateItemsNode.isArray()) {
            throw new IllegalStateException("ClinicalText candidate selector candidate_items must be an array");
        }
        if (STATUS_SKIPPED.equals(status)) {
            return new CandidateSelection(List.of());
        }

        List<CandidateItem> candidateItems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : candidateItemsNode) {
            if (!item.isObject()) {
                continue;
            }
            String sourceText = item.path("source_text").asText("");
            if (!StringUtils.hasText(sourceText)) {
                continue;
            }
            NotePayload note = resolveNotePayload(item.path("note_ref").asText(""), sourceText, notesByRef);
            if (note == null) {
                continue;
            }
            String normalizedSourceText = normalizeForSearch(sourceText);
            String key = note.noteRef() + "|" + normalizedSourceText;
            if (!StringUtils.hasText(normalizedSourceText) || !seen.add(key)) {
                continue;
            }
            candidateItems.add(new CandidateItem(note, sourceText.trim(), item.path("reason_tag").asText("other")));
            if (candidateItems.size() >= MAX_CANDIDATE_ITEMS) {
                break;
            }
        }

        if (candidateItems.isEmpty() && !candidateItemsNode.isEmpty()) {
            throw new IllegalStateException("ClinicalText candidate selector returned no traceable candidates");
        }
        return new CandidateSelection(candidateItems);
    }

    private NotePayload resolveNotePayload(String noteRef, String sourceText, Map<String, NotePayload> notesByRef) {
        if (StringUtils.hasText(noteRef)) {
            NotePayload note = notesByRef.get(noteRef.trim());
            if (note != null && containsNormalized(note.noteText(), sourceText)) {
                return note;
            }
        }

        NotePayload matched = null;
        for (NotePayload note : notesByRef.values()) {
            if (!containsNormalized(note.noteText(), sourceText)) {
                continue;
            }
            if (matched != null) {
                return null;
            }
            matched = note;
        }
        return matched;
    }

    private EvidenceBlock buildCandidateBlock(EvidenceBlock originalBlock, List<CandidateItem> candidateItems) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("note_count", uniqueNotes(candidateItems).size());
        payload.put("note_text", mergeCandidateText(candidateItems));
        payload.set("note_ids", toArray(uniqueNoteValues(candidateItems, NotePayload::noteId)));
        payload.set("note_types", toArray(uniqueNoteValues(candidateItems, NotePayload::noteType)));
        payload.set("note_times", toArray(uniqueNoteValues(candidateItems, NotePayload::noteTime)));
        payload.set("note_refs", toArray(uniqueNoteValues(candidateItems, NotePayload::noteRef)));
        payload.put("selection_source", "llm_candidate_selector");
        payload.put("candidate_count", candidateItems.size());
        payload.put("original_source_ref", originalBlock.sourceRef());
        payload.set("candidate_items", buildCandidateItems(candidateItems));
        return new EvidenceBlock(
                originalBlock.blockKey(),
                originalBlock.reqno(),
                originalBlock.rawDataId(),
                originalBlock.dataDate(),
                originalBlock.blockType(),
                originalBlock.sourceType(),
                originalBlock.sourceRef(),
                originalBlock.title(),
                toJson(payload),
                originalBlock.contextOnly()
        );
    }

    private List<EvidenceBlock> mergeCandidateBlocks(PatientRawDataEntity rawData, List<EvidenceBlock> blocks) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<EvidenceBlock> result = new ArrayList<>();
        List<EvidenceBlock> buffer = new ArrayList<>();
        int totalLength = 0;
        for (EvidenceBlock block : blocks) {
            if (!isMergeableCandidateBlock(block)) {
                flushMergedCandidateBlocks(rawData, result, buffer);
                buffer.clear();
                totalLength = 0;
                result.add(block);
                continue;
            }
            int noteTextLength = candidateNoteText(block).length();
            int candidateLength = buffer.isEmpty() ? noteTextLength : totalLength + 2 + noteTextLength;
            if (!buffer.isEmpty() && candidateLength > MAX_NOTE_TEXT_LENGTH) {
                flushMergedCandidateBlocks(rawData, result, buffer);
                buffer.clear();
                totalLength = 0;
            }
            buffer.add(block);
            totalLength = buffer.size() == 1 ? noteTextLength : totalLength + 2 + noteTextLength;
        }
        flushMergedCandidateBlocks(rawData, result, buffer);
        return List.copyOf(result);
    }

    private void flushMergedCandidateBlocks(PatientRawDataEntity rawData,
                                            List<EvidenceBlock> result,
                                            List<EvidenceBlock> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        if (buffer.size() == 1) {
            result.add(buffer.get(0));
            return;
        }
        result.add(toMergedCandidateBlock(rawData, buffer));
    }

    private EvidenceBlock toMergedCandidateBlock(PatientRawDataEntity rawData, List<EvidenceBlock> blocks) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("note_text", mergeCandidateBlockText(blocks));
        ArrayNode noteIds = objectMapper.createArrayNode();
        ArrayNode noteTypes = objectMapper.createArrayNode();
        ArrayNode noteTimes = objectMapper.createArrayNode();
        ArrayNode noteRefs = objectMapper.createArrayNode();
        ArrayNode originalSourceRefs = objectMapper.createArrayNode();
        ArrayNode candidateItems = objectMapper.createArrayNode();
        Set<String> seenNoteIds = new LinkedHashSet<>();
        Set<String> seenNoteTypes = new LinkedHashSet<>();
        Set<String> seenNoteTimes = new LinkedHashSet<>();
        Set<String> seenNoteRefs = new LinkedHashSet<>();
        Set<String> seenOriginalSourceRefs = new LinkedHashSet<>();
        Set<String> seenCandidateItems = new LinkedHashSet<>();
        for (EvidenceBlock block : blocks) {
            ObjectNode blockPayload = parseObject(block.payloadJson(), "clinical_text_candidate_payload", block.rawDataId());
            appendUniqueTextArray(noteIds, seenNoteIds, blockPayload.path("note_ids"));
            appendUniqueTextArray(noteTypes, seenNoteTypes, blockPayload.path("note_types"));
            appendUniqueTextArray(noteTimes, seenNoteTimes, blockPayload.path("note_times"));
            appendUniqueTextArray(noteRefs, seenNoteRefs, blockPayload.path("note_refs"));
            appendUniqueText(originalSourceRefs, seenOriginalSourceRefs, block.sourceRef());
            appendUniqueCandidateItems(candidateItems, seenCandidateItems, blockPayload.path("candidate_items"));
        }
        payload.put("note_count", noteRefs.size());
        payload.set("note_ids", noteIds);
        payload.set("note_types", noteTypes);
        payload.set("note_times", noteTimes);
        payload.set("note_refs", noteRefs);
        payload.put("selection_source", "llm_candidate_selector");
        payload.put("candidate_count", candidateItems.size());
        payload.put("original_source_ref", buildMergedOriginalSourceRef(blocks));
        payload.set("original_source_refs", originalSourceRefs);
        payload.set("candidate_items", candidateItems);
        return createBlock(rawData,
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                buildMergedCandidateSourceRef(noteRefs, blocks),
                "clinical_note_candidates_batch",
                payload,
                false);
    }

    private boolean isMergeableCandidateBlock(EvidenceBlock block) {
        if (block == null || block.blockType() != EvidenceBlockType.CLINICAL_TEXT) {
            return false;
        }
        ObjectNode payload = parseObject(block.payloadJson(), "clinical_text_candidate_payload", block.rawDataId());
        return "llm_candidate_selector".equals(payload.path("selection_source").asText(""))
                && payload.path("candidate_count").asInt(0) > 0
                && payload.path("candidate_items").isArray()
                && StringUtils.hasText(payload.path("note_text").asText(""));
    }

    private String candidateNoteText(EvidenceBlock block) {
        ObjectNode payload = parseObject(block.payloadJson(), "clinical_text_candidate_payload", block.rawDataId());
        return payload.path("note_text").asText("");
    }

    private String mergeCandidateBlockText(List<EvidenceBlock> blocks) {
        StringBuilder builder = new StringBuilder();
        for (EvidenceBlock block : blocks) {
            String noteText = candidateNoteText(block);
            if (!StringUtils.hasText(noteText)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(noteText.trim());
        }
        return builder.toString();
    }

    private void appendUniqueTextArray(ArrayNode target, Set<String> seen, JsonNode source) {
        if (source == null || !source.isArray()) {
            return;
        }
        for (JsonNode item : source) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            appendUniqueText(target, seen, item.asText(""));
        }
    }

    private void appendUniqueText(ArrayNode target, Set<String> seen, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String text = value.trim();
        String normalized = normalizeForSearch(text);
        if (StringUtils.hasText(normalized) && seen.add(normalized)) {
            target.add(text);
        }
    }

    private void appendUniqueCandidateItems(ArrayNode target, Set<String> seen, JsonNode source) {
        if (source == null || !source.isArray()) {
            return;
        }
        for (JsonNode item : source) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String noteRef = item.path("note_ref").asText("");
            String sourceText = item.path("source_text").asText("");
            String key = normalizeForSearch(noteRef) + "|" + normalizeForSearch(sourceText);
            if (!StringUtils.hasText(sourceText) || !seen.add(key)) {
                continue;
            }
            target.add(item.deepCopy());
        }
    }

    private String buildMergedCandidateSourceRef(ArrayNode noteRefs, List<EvidenceBlock> blocks) {
        List<String> refs = new ArrayList<>();
        for (JsonNode noteRef : noteRefs) {
            if (noteRef != null && noteRef.isTextual() && StringUtils.hasText(noteRef.asText())) {
                refs.add(noteRef.asText().trim());
            }
        }
        if (!refs.isEmpty()) {
            return "pat_illnessCourse." + buildRangeRef(refs);
        }
        return buildMergedOriginalSourceRef(blocks);
    }

    private String buildMergedOriginalSourceRef(List<EvidenceBlock> blocks) {
        List<String> refs = blocks.stream()
                .map(EvidenceBlock::sourceRef)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        return refs.isEmpty() ? "pat_illnessCourse.candidates" : buildRangeRef(refs);
    }

    private String buildRangeRef(List<String> refs) {
        if (refs.size() == 1 || refs.get(0).equals(refs.get(refs.size() - 1))) {
            return refs.get(0);
        }
        return refs.get(0) + "~" + refs.get(refs.size() - 1);
    }

    private ArrayNode buildCandidateItems(List<CandidateItem> candidateItems) {
        ArrayNode result = objectMapper.createArrayNode();
        for (CandidateItem item : candidateItems) {
            ObjectNode node = result.addObject();
            node.put("note_ref", item.note().noteRef());
            node.put("note_id", item.note().noteId());
            node.put("note_type", item.note().noteType());
            node.put("note_time", item.note().noteTime());
            node.put("source_text", item.sourceText());
            node.put("reason_tag", StringUtils.hasText(item.reasonTag()) ? item.reasonTag().trim() : "other");
        }
        return result;
    }

    private String mergeCandidateText(List<CandidateItem> candidateItems) {
        StringBuilder builder = new StringBuilder();
        for (CandidateItem item : candidateItems) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("[");
            builder.append(item.note().noteRef());
            if (StringUtils.hasText(item.note().noteType())) {
                builder.append(" ").append(item.note().noteType().trim());
            }
            if (StringUtils.hasText(item.note().noteTime())) {
                builder.append(" ").append(item.note().noteTime().trim());
            }
            builder.append("]\n");
            builder.append(item.sourceText());
        }
        return builder.toString();
    }

    private List<NotePayload> uniqueNotes(List<CandidateItem> candidateItems) {
        Map<String, NotePayload> result = new LinkedHashMap<>();
        for (CandidateItem item : candidateItems) {
            result.putIfAbsent(item.note().noteRef(), item.note());
        }
        return List.copyOf(result.values());
    }

    private List<String> uniqueNoteValues(List<CandidateItem> candidateItems, NoteValueExtractor extractor) {
        Set<String> result = new LinkedHashSet<>();
        for (NotePayload note : uniqueNotes(candidateItems)) {
            String value = extractor.extract(note);
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    private Map<String, NotePayload> extractNotePayloads(JsonNode noteItemsNode) {
        Map<String, NotePayload> result = new LinkedHashMap<>();
        if (noteItemsNode == null || !noteItemsNode.isArray()) {
            return result;
        }
        for (JsonNode item : noteItemsNode) {
            if (!item.isObject()) {
                continue;
            }
            String noteRef = item.path("note_ref").asText("");
            String noteText = item.path("note_text").asText("");
            if (!StringUtils.hasText(noteRef) || !StringUtils.hasText(noteText)) {
                continue;
            }
            result.putIfAbsent(noteRef.trim(), new NotePayload(
                    noteRef.trim(),
                    item.path("note_id").asText(""),
                    item.path("note_type").asText(""),
                    item.path("note_time").asText(""),
                    noteText.trim()
            ));
        }
        return result;
    }

    private EvidenceBlock stripSelectorOnlyFields(EvidenceBlock block) {
        ObjectNode payload = parseObject(block.payloadJson(), "clinical_text_block_payload", block.rawDataId());
        if (!payload.has("note_items")) {
            return block;
        }
        payload.remove("note_items");
        return new EvidenceBlock(
                block.blockKey(),
                block.reqno(),
                block.rawDataId(),
                block.dataDate(),
                block.blockType(),
                block.sourceType(),
                block.sourceRef(),
                block.title(),
                toJson(payload),
                block.contextOnly()
        );
    }

    private boolean containsNormalized(String haystack, String needle) {
        String normalizedHaystack = normalizeForSearch(haystack);
        String normalizedNeedle = normalizeForSearch(needle);
        return StringUtils.hasText(normalizedNeedle) && normalizedHaystack.contains(normalizedNeedle);
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

    private NoteItem toNoteItem(JsonNode note, int index) {
        if (!hasMeaningfulContent(note)) {
            return null;
        }
        String noteText = note.path("illnesscontent").asText("");
        if (!StringUtils.hasText(noteText)) {
            return null;
        }
        String noteType = note.path("itemname").asText("");
        String noteTime = firstText(note, "creattime", "changetime");
        String noteId = firstText(note, "illnessCourseId");
        return new NoteItem(
                buildNoteRef(noteId, noteType, noteTime, index),
                noteId,
                noteType,
                noteTime,
                noteText.trim()
        );
    }

    private EvidenceBlock toBlock(PatientRawDataEntity rawData, List<NoteItem> notes) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("note_count", notes.size());
        payload.put("note_text", mergeNoteText(notes));
        payload.set("note_ids", toArray(notes.stream().map(NoteItem::noteId).toList()));
        payload.set("note_types", toArray(notes.stream().map(NoteItem::noteType).toList()));
        payload.set("note_times", toArray(notes.stream().map(NoteItem::noteTime).toList()));
        payload.set("note_refs", toArray(notes.stream().map(NoteItem::noteRef).toList()));
        payload.set("note_items", buildNoteItems(notes));
        String sourceRef = "pat_illnessCourse." + buildMergedRef(notes);
        return createBlock(rawData,
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                sourceRef,
                buildMergedTitle(notes),
                payload,
                false);
    }

    private String mergeNoteText(List<NoteItem> notes) {
        StringBuilder builder = new StringBuilder();
        for (NoteItem item : notes) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            if (StringUtils.hasText(item.noteType()) || StringUtils.hasText(item.noteTime())) {
                builder.append("[");
                if (StringUtils.hasText(item.noteType())) {
                    builder.append(item.noteType().trim());
                }
                if (StringUtils.hasText(item.noteType()) && StringUtils.hasText(item.noteTime())) {
                    builder.append(" ");
                }
                if (StringUtils.hasText(item.noteTime())) {
                    builder.append(item.noteTime().trim());
                }
                builder.append("]\n");
            }
            builder.append(item.noteText());
        }
        return builder.toString();
    }

    private ArrayNode buildNoteItems(List<NoteItem> notes) {
        ArrayNode result = objectMapper.createArrayNode();
        for (NoteItem note : notes) {
            ObjectNode node = result.addObject();
            node.put("note_ref", note.noteRef());
            node.put("note_id", note.noteId());
            node.put("note_type", note.noteType());
            node.put("note_time", note.noteTime());
            node.put("note_text", note.noteText());
        }
        return result;
    }

    private ArrayNode toArray(List<String> values) {
        ArrayNode result = objectMapper.createArrayNode();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private String buildMergedRef(List<NoteItem> notes) {
        if (notes.size() == 1) {
            return notes.get(0).noteRef();
        }
        return notes.get(0).noteRef() + "~" + notes.get(notes.size() - 1).noteRef();
    }

    private String buildMergedTitle(List<NoteItem> notes) {
        String firstType = notes.stream()
                .map(NoteItem::noteType)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("clinical_note");
        return notes.size() == 1 ? firstType : firstType + "_batch";
    }

    private record NoteItem(
            String noteRef,
            String noteId,
            String noteType,
            String noteTime,
            String noteText
    ) {
    }

    private record NotePayload(
            String noteRef,
            String noteId,
            String noteType,
            String noteTime,
            String noteText
    ) {
    }

    private record CandidateItem(
            NotePayload note,
            String sourceText,
            String reasonTag
    ) {
    }

    private record CandidateSelection(
            List<CandidateItem> items
    ) {
    }

    @FunctionalInterface
    private interface NoteValueExtractor {
        String extract(NotePayload note);
    }
}
