package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClinicalTextBlockBuilder extends AbstractEvidenceBlockBuilder {

    private static final int MAX_NOTE_TEXT_LENGTH = 5000;

    public ClinicalTextBlockBuilder(ObjectMapper objectMapper) {
        super(objectMapper);
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

        return List.copyOf(buildMergedBlocks(rawData, notes));
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
}
