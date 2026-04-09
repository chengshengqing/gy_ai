package com.zzhy.yg_ai.domain.normalize.facts.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzhy.yg_ai.domain.normalize.support.NotePreparationSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RiskCandidateBuilder extends AbstractStructuredNoteFactsBuilder {

    public RiskCandidateBuilder(NotePreparationSupport notePreparationSupport) {
        super(notePreparationSupport);
    }

    public List<Map<String, Object>> buildDetailedRiskCandidates(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StructuredNoteContext note : structuredNotes(standardizedDayFacts)) {
            JsonNode node = note.structuredNode();
            JsonNode riskAlerts = node.path("risk_alerts");
            if (riskAlerts.isArray()) {
                for (JsonNode item : riskAlerts) {
                    String risk = item.path("risk").asText("").trim();
                    if (!StringUtils.hasText(risk)) {
                        continue;
                    }
                    Map<String, Object> riskItem = new LinkedHashMap<>();
                    riskItem.put("risk", risk);
                    riskItem.put("source_types", List.of(note.noteType()));
                    riskItem.put("source_note_refs", List.of(note.noteRef()));
                    result.add(riskItem);
                }
            }
            JsonNode coreProblems = node.path("core_problems");
            if (coreProblems.isArray()) {
                for (JsonNode item : coreProblems) {
                    JsonNode risks = item.path("risk");
                    if (risks.isArray()) {
                        for (JsonNode risk : risks) {
                            String riskText = risk.asText("").trim();
                            if (!StringUtils.hasText(riskText)) {
                                continue;
                            }
                            Map<String, Object> riskItem = new LinkedHashMap<>();
                            riskItem.put("risk", riskText);
                            riskItem.put("source_types", List.of(note.noteType()));
                            riskItem.put("source_note_refs", List.of(note.noteRef()));
                            result.add(riskItem);
                        }
                    }
                }
            }
        }
        return notes().deduplicateMapList(result, "risk", 12);
    }
}
