package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClinicalTextBlockBuilder extends AbstractEvidenceBlockBuilder {

    public ClinicalTextBlockBuilder(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public List<EvidenceBlock> build(PatientRawDataEntity rawData, PatientSummaryEntity latestSummary) {
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

        List<EvidenceBlock> result = new ArrayList<>();
        int index = 0;
        for (JsonNode note : notes) {
            if (!hasMeaningfulContent(note)) {
                index++;
                continue;
            }
            String noteText = note.path("illnesscontent").asText("");
            if (!StringUtils.hasText(noteText)) {
                index++;
                continue;
            }
            String noteType = note.path("itemname").asText("");
            String noteTime = firstText(note, "creattime", "changetime");
            String noteId = firstText(note, "illnessCourseId");
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("note_id", noteId);
            payload.put("note_type", noteType);
            payload.put("note_time", noteTime);
            payload.put("note_text", noteText);
            payload.set("data", note.deepCopy());
            String sourceRef = "pat_illnessCourse." + buildNoteRef(noteId, noteType, noteTime, index);
            result.add(createBlock(rawData,
                    EvidenceBlockType.CLINICAL_TEXT,
                    InfectionSourceType.RAW,
                    sourceRef,
                    StringUtils.hasText(noteType) ? noteType : "clinical_note",
                    payload,
                    false));
            index++;
        }
        return List.copyOf(result);
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
}
