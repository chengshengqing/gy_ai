package com.zzhy.yg_ai.domain.normalize.assemble;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptCatalog;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptDefinition;
import com.zzhy.yg_ai.domain.normalize.support.NoteTypePriorityResolver;
import com.zzhy.yg_ai.domain.normalize.validation.NormalizeOutputValidator;
import com.zzhy.yg_ai.domain.normalize.validation.NormalizeValidatedResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class NormalizeNoteStructAssembler {

    private final ObjectMapper objectMapper;
    private final NormalizePromptCatalog promptCatalog;
    private final IllnessCourseTimeResolver illnessCourseTimeResolver;
    private final NormalizeOutputValidator normalizeOutputValidator;
    private final NoteTypePriorityResolver noteTypePriorityResolver;

    public NormalizeNoteStructureResult assemble(JsonNode rawRoot, PatientRawDataEntity rawData) {
        List<PatientCourseData.PatIllnessCourse> illnessCourseList = objectMapper.convertValue(
                rawRoot.path("pat_illnessCourse"),
                new TypeReference<List<PatientCourseData.PatIllnessCourse>>() {
                });
        if (illnessCourseList == null || illnessCourseList.isEmpty()) {
            return new NormalizeNoteStructureResult(List.of(), List.of());
        }

        List<Map<String, Object>> noteStructuredList = new ArrayList<>();
        List<Map<String, Object>> noteValidationList = new ArrayList<>();
        for (PatientCourseData.PatIllnessCourse illnessCourse : illnessCourseList) {
            if (illnessCourse == null || !StringUtils.hasText(illnessCourse.getIllnesscontent())) {
                continue;
            }
            String noteType = illnessCourse.getItemname();
            String noteTime = illnessCourseTimeResolver.resolve(illnessCourse, rawData);
            NormalizeValidatedResult llmResult = callNotePrompt(illnessCourse, noteType, noteTime);
            noteStructuredList.add(buildStructuredNote(noteType, noteTime, llmResult));
            noteValidationList.add(buildValidationNote(noteType, noteTime, llmResult));
        }
        noteStructuredList.sort(Comparator.comparingInt(
                item -> noteTypePriorityResolver.resolvePriority(String.valueOf(item.get("note_type")))));
        return new NormalizeNoteStructureResult(noteStructuredList, noteValidationList);
    }

    private NormalizeValidatedResult callNotePrompt(PatientCourseData.PatIllnessCourse illnessCourse,
                                                    String noteType,
                                                    String noteTime) {
        NormalizePromptDefinition promptDefinition = promptCatalog.selectIllnessPrompt(noteType);
        Map<String, Object> currentIllness = new LinkedHashMap<>();
        currentIllness.put("itemname", noteType);
        currentIllness.put("time", noteTime);
        currentIllness.put("illnesscontent", illnessCourse.getIllnesscontent());
        return normalizeOutputValidator.validateWithRetry(promptDefinition, toJson(currentIllness));
    }

    private Map<String, Object> buildStructuredNote(String noteType,
                                                    String noteTime,
                                                    NormalizeValidatedResult llmResult) {
        Map<String, Object> noteStructured = new LinkedHashMap<>();
        noteStructured.put("note_type", noteType);
        noteStructured.put("timestamp", noteTime);
        noteStructured.put("structured", llmResult.success()
                ? llmResult.outputNode()
                : objectMapper.createObjectNode());
        noteStructured.put("validation", llmResult.toReport());
        return noteStructured;
    }

    private Map<String, Object> buildValidationNote(String noteType,
                                                    String noteTime,
                                                    NormalizeValidatedResult llmResult) {
        return Map.of(
                "note_type", defaultIfBlank(noteType, ""),
                "timestamp", defaultIfBlank(noteTime, ""),
                "validation", llmResult.toReport()
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
