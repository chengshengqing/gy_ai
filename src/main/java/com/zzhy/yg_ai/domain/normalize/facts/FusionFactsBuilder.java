package com.zzhy.yg_ai.domain.normalize.facts;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.normalize.facts.candidate.AbstractStructuredNoteFactsBuilder;
import com.zzhy.yg_ai.domain.normalize.facts.candidate.ProblemCandidateBuilder;
import com.zzhy.yg_ai.domain.normalize.facts.candidate.RiskCandidateBuilder;
import com.zzhy.yg_ai.domain.normalize.support.NotePreparationSupport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class FusionFactsBuilder extends AbstractStructuredNoteFactsBuilder {

    private final ProblemCandidateBuilder problemCandidateBuilder;
    private final RiskCandidateBuilder riskCandidateBuilder;

    public FusionFactsBuilder(NotePreparationSupport notePreparationSupport,
                              ProblemCandidateBuilder problemCandidateBuilder,
                              RiskCandidateBuilder riskCandidateBuilder) {
        super(notePreparationSupport);
        this.problemCandidateBuilder = problemCandidateBuilder;
        this.riskCandidateBuilder = riskCandidateBuilder;
    }

    public boolean canGenerateDailyFusion(Map<String, Object> standardizedDayFacts) {
        if (standardizedDayFacts == null || standardizedDayFacts.isEmpty()) {
            return false;
        }
        Object structured = standardizedDayFacts.get("structured");
        if (structured instanceof Collection<?> structuredNotes && !structuredNotes.isEmpty()) {
            return true;
        }
        Object dataPresence = standardizedDayFacts.get("data_presence");
        if (dataPresence instanceof Map<?, ?> presenceMap) {
            Object count = presenceMap.get("objective_source_count");
            if (count instanceof Number number) {
                return number.intValue() >= 2;
            }
        }
        return false;
    }

    public Map<String, Object> buildFusionReadyFacts(Map<String, Object> standardizedDayFacts, PatientRawDataEntity rawData) {
        Map<String, Object> fusionFacts = new LinkedHashMap<>();
        fusionFacts.put("reqno", rawData == null ? "" : notes().defaultIfBlank(rawData.getReqno(), ""));
        fusionFacts.put("date", rawData == null || rawData.getDataDate() == null ? "" : rawData.getDataDate().toString());
        fusionFacts.put("meta", buildFusionMeta(standardizedDayFacts));

        Map<String, Object> clinicalReasoningLayer = new LinkedHashMap<>();
        clinicalReasoningLayer.put("problem_candidates", problemCandidateBuilder.buildProblemCandidates(standardizedDayFacts));
        clinicalReasoningLayer.put("differential_candidates", problemCandidateBuilder.buildDifferentialCandidates(standardizedDayFacts));
        clinicalReasoningLayer.put("etiology_candidates", problemCandidateBuilder.buildEtiologyCandidates(standardizedDayFacts));
        clinicalReasoningLayer.put("risk_candidates", riskCandidateBuilder.buildDetailedRiskCandidates(standardizedDayFacts));
        clinicalReasoningLayer.put("pending_facts", problemCandidateBuilder.buildPendingFacts(standardizedDayFacts));
        fusionFacts.put("clinical_reasoning_layer", clinicalReasoningLayer);

        Map<String, Object> objectiveFactLayer = new LinkedHashMap<>();
        objectiveFactLayer.put("diagnosis_facts", standardizedDayFacts.getOrDefault("diagnosis_facts", List.of()));
        objectiveFactLayer.put("vitals_summary", standardizedDayFacts.getOrDefault("vitals_summary", Map.of()));
        objectiveFactLayer.put("lab_summary", standardizedDayFacts.getOrDefault("lab_summary", Map.of()));
        objectiveFactLayer.put("imaging_summary", standardizedDayFacts.getOrDefault("imaging_summary", List.of()));
        objectiveFactLayer.put("orders_summary", standardizedDayFacts.getOrDefault("orders_summary", Map.of()));
        objectiveFactLayer.put("objective_events", standardizedDayFacts.getOrDefault("objective_events", List.of()));
        objectiveFactLayer.put("objective_evidence", problemCandidateBuilder.buildObjectiveEvidence(standardizedDayFacts));
        objectiveFactLayer.put("action_facts", problemCandidateBuilder.buildActionFacts(standardizedDayFacts));
        fusionFacts.put("objective_fact_layer", objectiveFactLayer);

        fusionFacts.put("fusion_control_layer", Map.of());
        return fusionFacts;
    }

    private Map<String, Object> buildFusionMeta(Map<String, Object> standardizedDayFacts) {
        Map<String, Object> meta = new LinkedHashMap<>();
        List<StructuredNoteContext> notes = structuredNotes(standardizedDayFacts);
        meta.put("has_admission_note", notes.stream().anyMatch(note -> containsAny(note.noteType(), "首次病程", "入院记录")));
        meta.put("has_progress_note", notes.stream().anyMatch(note -> containsAny(note.noteType(), "日常病程")));
        meta.put("has_consultation_note", notes.stream().anyMatch(note -> containsAny(note.noteType(), "会诊")));
        meta.put("has_procedure_note", notes.stream().anyMatch(note -> containsAny(note.noteType(), "手术")));
        List<String> refs = new ArrayList<>();
        for (StructuredNoteContext note : notes) {
            refs.add(note.noteRef());
        }
        meta.put("source_note_refs", deduplicateKeepOrder(refs, 20));
        return meta;
    }

    private List<String> deduplicateKeepOrder(List<String> values, int maxSize) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                unique.add(value.trim());
                if (unique.size() >= maxSize) {
                    break;
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
