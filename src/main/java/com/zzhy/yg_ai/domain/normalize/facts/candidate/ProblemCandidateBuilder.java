package com.zzhy.yg_ai.domain.normalize.facts.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzhy.yg_ai.domain.normalize.support.NotePreparationSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProblemCandidateBuilder extends AbstractStructuredNoteFactsBuilder {

    public ProblemCandidateBuilder(NotePreparationSupport notePreparationSupport) {
        super(notePreparationSupport);
    }

    public List<Map<String, Object>> buildProblemCandidates(Map<String, Object> standardizedDayFacts) {
        LinkedHashMap<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        Object diagnosisFacts = standardizedDayFacts.get("diagnosis_facts");
        if (diagnosisFacts instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> source)) {
                    continue;
                }
                String name = notes().valueAsString(source.get("name")).trim();
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("problem", name);
                candidate.put("source_types", List.of(notes().defaultIfBlank(
                        notes().valueAsString(source.get("source")), "diagnosis")));
                candidate.put("source_note_refs", List.of());
                mergeProblemCandidate(candidates, candidate);
                if (candidates.size() >= 10) {
                    break;
                }
            }
        }
        for (StructuredNoteContext note : structuredNotes(standardizedDayFacts)) {
            extractProblemCandidatesFromStructured(note.structuredNode(), note.noteType(), note.noteRef(), candidates);
            if (candidates.size() >= 10) {
                break;
            }
        }
        return new ArrayList<>(candidates.values()).subList(0, Math.min(candidates.size(), 10));
    }

    public List<Map<String, Object>> buildDifferentialCandidates(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StructuredNoteContext note : structuredNotes(standardizedDayFacts)) {
            JsonNode node = note.structuredNode();
            JsonNode differential = node.path("differential_diagnosis");
            if (!differential.isArray()) {
                continue;
            }
            for (JsonNode item : differential) {
                String diagnosis = item.path("diagnosis").asText("").trim();
                if (!StringUtils.hasText(diagnosis)) {
                    continue;
                }
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("diagnosis", diagnosis);
                candidate.put("certainty", item.path("certainty").asText(""));
                candidate.put("reason", item.path("reason").asText(""));
                candidate.put("source_types", List.of(note.noteType()));
                candidate.put("source_note_refs", List.of(note.noteRef()));
                result.add(candidate);
            }
        }
        return notes().deduplicateMapList(result, "diagnosis", 10);
    }

    public List<Map<String, Object>> buildEtiologyCandidates(Map<String, Object> standardizedDayFacts) {
        return List.of();
    }

    public List<Map<String, Object>> buildObjectiveEvidence(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StructuredNoteContext note : structuredNotes(standardizedDayFacts)) {
            JsonNode node = note.structuredNode();
            LinkedHashSet<String> values = new LinkedHashSet<>();
            notes().collectFieldText(node, values, "new_findings", "worsening_points", "improving_points",
                    "key_exam_changes", "key_supporting_evidence", "critical_exam_or_pathology");
            JsonNode keyFindings = node.path("key_findings");
            if (keyFindings.isObject()) {
                notes().flattenText(keyFindings.path("vitals"), values);
                notes().flattenText(keyFindings.path("exam"), values);
                notes().flattenText(keyFindings.path("labs"), values);
                notes().flattenText(keyFindings.path("imaging"), values);
            }
            for (String value : values) {
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fact", value);
                item.put("source_types", List.of(note.noteType()));
                item.put("source_note_refs", List.of(note.noteRef()));
                result.add(item);
            }
        }
        return notes().deduplicateMapList(result, "fact", 24);
    }

    public List<Map<String, Object>> buildActionFacts(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StructuredNoteContext note : structuredNotes(standardizedDayFacts)) {
            JsonNode node = note.structuredNode();
            LinkedHashSet<String> actions = new LinkedHashSet<>();
            JsonNode plan = node.path("initial_plan");
            if (plan.isObject()) {
                notes().flattenText(plan.path("treatment"), actions);
            }
            notes().collectFieldText(node, actions, "treatment_adjustments", "urgent_actions",
                    "major_actions", "immediate_postop_orders");
            for (String action : actions) {
                if (!StringUtils.hasText(action)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("action", action);
                item.put("source_types", List.of(note.noteType()));
                item.put("source_note_refs", List.of(note.noteRef()));
                result.add(item);
            }
        }
        return notes().deduplicateMapList(result, "action", 16);
    }

    public List<Map<String, Object>> buildPendingFacts(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StructuredNoteContext note : structuredNotes(standardizedDayFacts)) {
            JsonNode node = note.structuredNode();
            LinkedHashSet<String> items = new LinkedHashSet<>();
            JsonNode plan = node.path("initial_plan");
            if (plan.isObject()) {
                notes().flattenText(plan.path("tests"), items);
                notes().flattenText(plan.path("monitoring"), items);
                notes().flattenText(plan.path("consults"), items);
                notes().flattenText(plan.path("procedures"), items);
                notes().flattenText(plan.path("education"), items);
            }
            notes().collectFieldText(node, items, "next_focus_24h", "short_term_plan_24h");
            JsonNode differential = node.path("differential_diagnosis");
            if (differential.isArray()) {
                for (JsonNode item : differential) {
                    String diagnosis = item.path("diagnosis").asText("").trim();
                    String reason = item.path("reason").asText("").trim();
                    if (!StringUtils.hasText(diagnosis)) {
                        continue;
                    }
                    items.add(StringUtils.hasText(reason) ? diagnosis + "：" + reason : diagnosis);
                }
            }
            for (String itemText : items) {
                if (!StringUtils.hasText(itemText)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("item", itemText);
                item.put("source_types", List.of(note.noteType()));
                item.put("source_note_refs", List.of(note.noteRef()));
                result.add(item);
            }
        }
        return notes().deduplicateMapList(result, "item", 16);
    }

    private void mergeProblemCandidate(Map<String, Map<String, Object>> collector, Map<String, Object> candidate) {
        String key = notes().valueAsString(candidate.get("problem")).trim();
        if (!StringUtils.hasText(key)) {
            return;
        }
        Map<String, Object> existing = collector.get(key);
        if (existing == null) {
            collector.put(key, candidate);
            return;
        }
        String certainty = preferNonBlank(
                notes().valueAsString(existing.get("certainty")),
                notes().valueAsString(candidate.get("certainty")));
        String status = preferNonBlank(
                notes().valueAsString(existing.get("status")),
                notes().valueAsString(candidate.get("status")));
        LinkedHashSet<String> mergedSources = new LinkedHashSet<>();
        notes().addAllStrings(mergedSources, existing.get("source_types"));
        notes().addAllStrings(mergedSources, candidate.get("source_types"));
        LinkedHashSet<String> mergedSourceRefs = new LinkedHashSet<>();
        notes().addAllStrings(mergedSourceRefs, existing.get("source_note_refs"));
        notes().addAllStrings(mergedSourceRefs, candidate.get("source_note_refs"));
        if (StringUtils.hasText(certainty)) {
            existing.put("certainty", certainty);
        }
        if (StringUtils.hasText(status)) {
            existing.put("status", status);
        }
        existing.put("source_types", new ArrayList<>(mergedSources));
        existing.put("source_note_refs", new ArrayList<>(mergedSourceRefs));
    }

    private void extractProblemCandidatesFromStructured(JsonNode node,
                                                        String noteType,
                                                        String noteRef,
                                                        Map<String, Map<String, Object>> collector) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        JsonNode coreProblems = node.path("core_problems");
        if (coreProblems.isArray()) {
            for (JsonNode item : coreProblems) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("problem", item.path("problem").asText(""));
                candidate.put("certainty", item.path("certainty").asText(""));
                candidate.put("status", item.path("time_status").asText(""));
                candidate.put("source_types", List.of(noteType));
                candidate.put("source_note_refs", List.of(noteRef));
                mergeProblemCandidate(collector, candidate);
            }
        }
        JsonNode consultCoreJudgment = node.path("consult_core_judgment");
        if (consultCoreJudgment.isArray()) {
            for (JsonNode item : consultCoreJudgment) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("problem", item.path("judgment").asText(""));
                candidate.put("certainty", item.path("certainty").asText(""));
                candidate.put("source_types", List.of(noteType));
                candidate.put("source_note_refs", List.of(noteRef));
                mergeProblemCandidate(collector, candidate);
            }
        }
        JsonNode differential = node.path("differential_diagnosis");
        if (differential.isArray()) {
            for (JsonNode item : differential) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("problem", item.path("diagnosis").asText(""));
                candidate.put("certainty", item.path("certainty").asText(""));
                candidate.put("source_types", List.of(noteType));
                candidate.put("source_note_refs", List.of(noteRef));
                mergeProblemCandidate(collector, candidate);
            }
        }
        JsonNode newProblemList = node.path("new_problem_list");
        if (newProblemList.isArray()) {
            for (JsonNode item : newProblemList) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("problem", item.asText(""));
                candidate.put("source_types", List.of(noteType));
                candidate.put("source_note_refs", List.of(noteRef));
                mergeProblemCandidate(collector, candidate);
            }
        }
    }

    private String preferNonBlank(String left, String right) {
        if (StringUtils.hasText(left)) {
            return left.trim();
        }
        if (StringUtils.hasText(right)) {
            return right.trim();
        }
        return "";
    }
}
