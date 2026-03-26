package com.zzhy.yg_ai.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PatientTimelineViewData {

    private String reqno = "";

    private List<TimelineItem> items = new ArrayList<>();

    @Data
    public static class TimelineItem {
        private String date = "";
        private String title = "";
        private String summary = "";
        private List<String> badges = new ArrayList<>();
        private String severity = "low";
        @JsonProperty("severity_label")
        private String severityLabel = "";
        @JsonProperty("is_key_day")
        private boolean keyDay;
        @JsonProperty("primary_problems")
        private List<ProblemItem> primaryProblems = new ArrayList<>();
        @JsonProperty("secondary_problems")
        private List<ProblemItem> secondaryProblems = new ArrayList<>();
        @JsonProperty("risk_items")
        private List<RiskItem> riskItems = new ArrayList<>();
        private ActionGroups actions = new ActionGroups();
        @JsonProperty("next_focus")
        private List<String> nextFocus = new ArrayList<>();
        @JsonProperty("evidence_highlights")
        private List<String> evidenceHighlights = new ArrayList<>();
        @JsonProperty("source_notes")
        private List<SourceNote> sourceNotes = new ArrayList<>();
        @JsonProperty("raw_ref")
        private RawRef rawRef = new RawRef();
    }

    @Data
    public static class ProblemItem {
        private String name = "";
        @JsonProperty("problem_key")
        private String problemKey = "";
        @JsonProperty("problem_type")
        private String problemType = "";
        @JsonProperty("problem_type_label")
        private String problemTypeLabel = "";
        private String status = "";
        @JsonProperty("status_label")
        private String statusLabel = "";
        private String certainty = "";
        @JsonProperty("certainty_label")
        private String certaintyLabel = "";
        private List<String> evidence = new ArrayList<>();
        private List<String> actions = new ArrayList<>();
        private List<String> risks = new ArrayList<>();
        private List<SourceNote> sources = new ArrayList<>();
    }

    @Data
    public static class RiskItem {
        private String name = "";
        private String basis = "";
        private List<SourceNote> sources = new ArrayList<>();
    }

    @Data
    public static class ActionGroups {
        private List<String> treatment = new ArrayList<>();
        private List<String> tests = new ArrayList<>();
        private List<String> monitoring = new ArrayList<>();
        private List<String> other = new ArrayList<>();
    }

    @Data
    public static class SourceNote {
        private String name = "";
        private String time = "";
    }

    @Data
    public static class RawRef {
        @JsonProperty("record_type")
        private String recordType = "";
        @JsonProperty("source_note_refs")
        private List<String> sourceNoteRefs = new ArrayList<>();
    }
}
