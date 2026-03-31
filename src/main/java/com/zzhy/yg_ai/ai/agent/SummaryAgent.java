package com.zzhy.yg_ai.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.ai.prompt.FormatAgentPrompt;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class SummaryAgent extends AbstractAgent {

    private static final int MODEL_TIMELINE_MAX_LENGTH = 8000;
    private static final DateTimeFormatter ILLNESS_TIME_FORMATTER = DateTimeUtils.DATE_TIME_FORMATTER;
    private static final Set<String> ALLOWED_STATUS = Set.of(
            "active", "acute_exacerbation", "worsening", "improving", "chronic", "stable", "clarified", "unclear"
    );
    private static final Set<String> ALLOWED_CERTAINTY = Set.of(
            "confirmed", "suspected", "possible", "workup_needed", "risk_only"
    );
    private static final Set<String> ALLOWED_PRIORITY = Set.of("high", "medium", "low");
    private static final Set<String> ALLOWED_PROBLEM_TYPE = Set.of(
            "disease", "complication", "chronic", "risk_state", "differential"
    );
    private static final Set<String> ALLOWED_BASELINE_CERTAINTY = Set.of("confirmed", "suspected", "workup_needed");
    private static final Set<String> ALLOWED_BASELINE_TIME_STATUS = Set.of("active", "acute_exacerbation", "chronic", "unclear");
    private static final Set<String> ALLOWED_SEVERITY = Set.of("mild", "moderate", "severe", "critical", "unclear");
    private static final Set<String> ALLOWED_DIFFERENTIAL_CERTAINTY = Set.of("suspected", "workup_needed");
    private static final Set<String> ALLOWED_RISK_ALERT_CERTAINTY = Set.of("risk_only", "suspected");
    private static final Set<String> ALLOWED_CONSULT_CERTAINTY = Set.of("confirmed", "suspected", "workup_needed", "risk_only");

    private final ObjectMapper objectMapper;

    public SummaryAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DailyIllnessResult extractDailyIllness(PatientSummaryEntity latestSummary, PatientRawDataEntity rawData) {
        if (rawData == null) {
            return new DailyIllnessResult("{}", "{}");
        }
        String baseSummaryJson = latestSummary == null || !StringUtils.hasText(latestSummary.getSummaryJson())
                ? "{}"
                : latestSummary.getSummaryJson();
        String rawInputJson = StringUtils.hasText(rawData.getFilterDataJson()) ? rawData.getFilterDataJson() : rawData.getDataJson();
        if (!StringUtils.hasText(rawInputJson)) {
            return new DailyIllnessResult("{}", baseSummaryJson);
        }

        JsonNode root = AgentUtils.parseToNode(rawInputJson);
        List<PatientCourseData.PatIllnessCourse> illnessCourseList = objectMapper.convertValue(
                root.path("pat_illnessCourse"),
                new TypeReference<List<PatientCourseData.PatIllnessCourse>>() {
                });

        List<Map<String, Object>> noteStructuredList = new ArrayList<>();
        List<Map<String, Object>> noteValidationList = new ArrayList<>();

        for (PatientCourseData.PatIllnessCourse illnessCourse : illnessCourseList) {
            String itemname = illnessCourse.getItemname();
            String illnesscontent = illnessCourse.getIllnesscontent();
            if (!StringUtils.hasText(illnesscontent)) {
                continue;
            }
            PromptDefinition promptDefinition = selectPromptByItemName(itemname);
            String noteTime = resolveIllnessTime(illnessCourse, rawData);

            Map<String, Object> currentIllness = new LinkedHashMap<>();
            currentIllness.put("itemname", itemname);
            currentIllness.put("time", noteTime);
            currentIllness.put("illnesscontent", illnesscontent);
            ValidatedLlmResult llmResult = callWithValidation(promptDefinition, AgentUtils.toJson(currentIllness));
            JsonNode normalizedResultNode = llmResult.success() ? llmResult.outputNode() : objectMapper.createObjectNode();

            Map<String, Object> noteStructured = new LinkedHashMap<>();
            noteStructured.put("note_type", itemname);
            noteStructured.put("timestamp", noteTime);
            noteStructured.put("structured", normalizedResultNode);
            noteStructured.put("validation", llmResult.toReport());
            noteStructuredList.add(noteStructured);
            noteValidationList.add(Map.of(
                    "note_type", defaultIfBlank(itemname, ""),
                    "timestamp", defaultIfBlank(noteTime, ""),
                    "validation", llmResult.toReport()
            ));
        }

        noteStructuredList.sort(Comparator.comparingInt(item -> noteTypePriority(String.valueOf(item.get("note_type")))));
        Map<String, Object> dayContext = buildStandardizedDayFacts(root, noteStructuredList);

        JsonNode validatedDailyFusionNode = objectMapper.createObjectNode();
        Map<String, Object> dailyFusionValidation = failureReport("daily_fusion skipped");
        if (canGenerateDailyFusion(dayContext)) {
            Map<String, Object> fusionReadyFactsInput = buildFusionReadyFacts(dayContext, rawData);
            JsonNode fusionReadyFactsNode = objectMapper.valueToTree(fusionReadyFactsInput);
            String userInput = AgentUtils.toJson(Map.of("fusion_ready_facts", fusionReadyFactsNode));
            dayContext.put("llm_input", userInput);
            ValidatedLlmResult dailyFusionResult = callWithValidation(dailyFusionPromptDefinition(), userInput);
            dailyFusionValidation = dailyFusionResult.toReport();
            if (dailyFusionResult.success()) {
                validatedDailyFusionNode = dailyFusionResult.outputNode();
            }
        }

        Map<String, Object> structData = new LinkedHashMap<>();
        structData.put("day_context", dayContext);
        structData.put("daily_fusion", validatedDailyFusionNode);
        structData.put("validation", Map.of(
                "notes", noteValidationList,
                "daily_fusion", dailyFusionValidation
        ));

        String structDataJson = AgentUtils.toJson(structData);
        List<Map<String, Object>> timelineAppendEntries = validatedDailyFusionNode.isObject() && validatedDailyFusionNode.size() > 0
                ? buildDailyFusionTimelineEntries(rawData, validatedDailyFusionNode)
                : Collections.emptyList();
        String updatedSummaryJson = appendTimelineEntries(baseSummaryJson, timelineAppendEntries, rawData.getReqno());
        return new DailyIllnessResult(structDataJson, updatedSummaryJson);
    }

    private Map<String, Object> buildStandardizedDayFacts(JsonNode rawRoot,
                                                          List<Map<String, Object>> noteStructuredList) {
        Map<String, Object> dayContext = new LinkedHashMap<>();
        dayContext.put("data_presence", buildDataPresence(rawRoot, noteStructuredList));
        dayContext.put("structured", noteStructuredList);
        dayContext.put("diagnosis_facts", buildDiagnosisFacts(rawRoot, noteStructuredList));
        dayContext.put("vitals_summary", summarizeVitals(rawRoot.path("vital_signs")));
        dayContext.put("lab_summary", summarizeLabs(rawRoot.path("lab_results")));
        dayContext.put("imaging_summary", summarizeImaging(rawRoot.path("imaging")));
        dayContext.put("orders_summary", summarizeDoctorOrders(rawRoot.path("doctor_orders")));
        dayContext.put("objective_events", buildObjectiveEvents(rawRoot));
        return dayContext;
    }

    private Map<String, Object> summarizeVitals(JsonNode vitalNode) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (vitalNode == null || !vitalNode.isArray() || vitalNode.isEmpty()) {
            summary.put("has_data", false);
            return summary;
        }

        double maxTemp = Double.NEGATIVE_INFINITY;
        double minTemp = Double.POSITIVE_INFINITY;
        double maxPulse = Double.NEGATIVE_INFINITY;
        double minPulse = Double.POSITIVE_INFINITY;
        int minSys = Integer.MAX_VALUE;
        int minDia = Integer.MAX_VALUE;
        int feverCount = 0;
        int abnormalBpCount = 0;
        int abnormalPulseCount = 0;

        for (JsonNode item : vitalNode) {
            double temp = parseDouble(firstNonBlank(
                    item.path("temp").asText(""),
                    item.path("temperature").asText("")
            ));
            if (!Double.isNaN(temp)) {
                maxTemp = Math.max(maxTemp, temp);
                minTemp = Math.min(minTemp, temp);
                if (temp >= 38.0D) {
                    feverCount++;
                }
            }
            double pulse = parseDouble(firstNonBlank(
                    item.path("pulse").asText("")
            ));
            if (!Double.isNaN(pulse)) {
                maxPulse = Math.max(maxPulse, pulse);
                minPulse = Math.min(minPulse, pulse);
                if (pulse > 100 || pulse < 50) {
                    abnormalPulseCount++;
                }
            }
            int[] bp = parseBloodPressure(firstNonBlank(
                    item.path("bp").asText(""),
                    item.path("blood_pressure").asText("")
            ));
            if (bp != null) {
                minSys = Math.min(minSys, bp[0]);
                minDia = Math.min(minDia, bp[1]);
                if (bp[0] < 90 || bp[1] < 60) {
                    abnormalBpCount++;
                }
            }
        }

        List<String> concise = new ArrayList<>();
        if (maxTemp != Double.NEGATIVE_INFINITY) {
            concise.add("体温最高 " + formatNumber(maxTemp) + "℃");
            concise.add("体温最低 " + formatNumber(minTemp) + "℃");
        }
        if (maxPulse != Double.NEGATIVE_INFINITY) {
            concise.add("脉搏最高 " + formatNumber(maxPulse) + " 次/分");
        }
        if (minSys != Integer.MAX_VALUE) {
            concise.add("血压最低 " + minSys + "/" + minDia + " mmHg");
        }
        if (feverCount >= 2) {
            concise.add("持续发热趋势");
        }
        summary.put("has_data", true);
        summary.put("min_max", concise);
        summary.put("most_abnormal", buildVitalMostAbnormal(maxTemp, maxPulse, minSys, minDia));
        summary.put("persistent_abnormal", feverCount >= 2 || abnormalBpCount >= 2 || abnormalPulseCount >= 2);
        summary.put("need_alert", maxTemp >= 39.0D || minSys <= 90 || maxPulse >= 120);
        return summary;
    }

    private Map<String, Object> summarizeDoctorOrders(JsonNode orderNode) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> longTermOrders = new ArrayList<>();
        List<String> temporaryOrders = new ArrayList<>();
        List<String> sgOrders = new ArrayList<>();
        List<String> allOrders = new ArrayList<>();
        if (orderNode != null && orderNode.isObject()) {
            collectArrayText(orderNode.get("long_term"), longTermOrders);
            collectArrayText(orderNode.get("temporary"), temporaryOrders);
            collectArrayText(orderNode.get("sg"), sgOrders);
        }
        allOrders.addAll(longTermOrders);
        allOrders.addAll(temporaryOrders);
        allOrders.addAll(sgOrders);
        result.put("has_data", !allOrders.isEmpty());
        result.put("long_term", deduplicateKeepOrder(longTermOrders, 20));
        result.put("temporary", deduplicateKeepOrder(temporaryOrders, 20));
        result.put("sg", deduplicateKeepOrder(sgOrders, 20));
        result.put("all_orders", deduplicateKeepOrder(allOrders, 40));
        return result;
    }

    private Map<String, Object> buildDataPresence(JsonNode rawRoot, List<Map<String, Object>> noteStructuredList) {
        Map<String, Object> dataPresence = new LinkedHashMap<>();
        boolean hasDiagnosis = hasNonEmptyNode(rawRoot.path("diagnosis"));
        boolean hasVitalSigns = hasNonEmptyNode(rawRoot.path("vital_signs"));
        boolean hasLabResults = hasLabData(rawRoot.path("lab_results"));
        boolean hasImaging = hasNonEmptyNode(rawRoot.path("imaging"));
        boolean hasDoctorOrders = hasDoctorOrderData(rawRoot.path("doctor_orders"));
        boolean hasPatIllnessCourse = (noteStructuredList != null && !noteStructuredList.isEmpty())
                || hasNonEmptyNode(rawRoot.path("pat_illnessCourse"));

        int objectiveSourceCount = 0;
        objectiveSourceCount += hasDiagnosis ? 1 : 0;
        objectiveSourceCount += hasVitalSigns ? 1 : 0;
        objectiveSourceCount += hasLabResults ? 1 : 0;
        objectiveSourceCount += hasImaging ? 1 : 0;
        objectiveSourceCount += hasDoctorOrders ? 1 : 0;

        dataPresence.put("has_pat_illness_course", hasPatIllnessCourse);
        dataPresence.put("has_diagnosis", hasDiagnosis);
        dataPresence.put("has_vital_signs", hasVitalSigns);
        dataPresence.put("has_lab_results", hasLabResults);
        dataPresence.put("has_imaging", hasImaging);
        dataPresence.put("has_doctor_orders", hasDoctorOrders);
        dataPresence.put("objective_source_count", objectiveSourceCount);
        return dataPresence;
    }

    private boolean canGenerateDailyFusion(Map<String, Object> standardizedDayFacts) {
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

    private List<Map<String, Object>> buildDiagnosisFacts(JsonNode rawRoot,
                                                          List<Map<String, Object>> noteStructuredList) {
        List<Map<String, Object>> diagnosisFacts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonNode diagnosisNode = rawRoot.path("diagnosis");
        if (diagnosisNode == null || !diagnosisNode.isArray()) {
            return diagnosisFacts;
        }
        for (JsonNode item : diagnosisNode) {
            String diagnosis = item == null ? "" : item.asText("").trim();
            if (!StringUtils.hasText(diagnosis) || !seen.add(diagnosis)) {
                continue;
            }
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("name", diagnosis);
            fact.put("source", "diagnosis");
            diagnosisFacts.add(fact);
        }
        return diagnosisFacts;
    }

    private Map<String, Object> summarizeLabs(JsonNode labNode) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> abnormalItems = new ArrayList<>();
        List<String> panelSummaries = new ArrayList<>();
        List<String> pathogenFindings = new ArrayList<>();
        List<String> trendFindings = new ArrayList<>();
        List<String> alertFlags = new ArrayList<>();
        if (labNode == null || !labNode.isObject()) {
            summary.put("has_data", false);
            return summary;
        }

        JsonNode testPanels = labNode.path("test_panels");
        if (testPanels.isArray()) {
            for (JsonNode panel : testPanels) {
                String panelName = firstNonBlank(
                        panel.path("panel_name").asText(""),
                        panel.path("name").asText(""),
                        panel.path("itemname").asText("")
                );
                List<String> panelResults = new ArrayList<>();
                JsonNode results = panel.path("results");
                if (results.isArray()) {
                    for (JsonNode result : results) {
                        String display = buildLabResultDisplay(result);
                        if (!StringUtils.hasText(display)) {
                            continue;
                        }
                        panelResults.add(display);
                        if (isLabResultAbnormal(result, display) && abnormalItems.size() < 12) {
                            abnormalItems.add(display);
                        }
                        if (containsAny(display, "危急", "极高", "极低", "阳性")) {
                            alertFlags.add(display);
                        }
                    }
                }
                if (StringUtils.hasText(panelName) && !panelResults.isEmpty() && panelSummaries.size() < 8) {
                    panelSummaries.add(panelName + "：" + String.join("；", panelResults.subList(0, Math.min(panelResults.size(), 3))));
                }
            }
        }

        JsonNode microbePanels = labNode.path("microbe_panels");
        if (microbePanels.isArray()) {
            for (JsonNode panel : microbePanels) {
                String specimen = firstNonBlank(
                        panel.path("specimen").asText(""),
                        panel.path("sample_type").asText(""),
                        panel.path("panel_name").asText("")
                );
                JsonNode results = panel.path("results");
                if (!results.isArray()) {
                    continue;
                }
                for (JsonNode result : results) {
                    String display = firstNonBlank(
                            result.path("display").asText(""),
                            result.path("organism").asText(""),
                            result.path("result").asText("")
                    );
                    if (!StringUtils.hasText(display)) {
                        continue;
                    }
                    String combined = StringUtils.hasText(specimen) ? specimen + "：" + display : display;
                    if (pathogenFindings.size() < 8) {
                        pathogenFindings.add(combined);
                    }
                    if (containsAny(combined, "阳性", "检出", "耐药")) {
                        alertFlags.add(combined);
                    }
                }
            }
        }

        summary.put("has_data", !abnormalItems.isEmpty() || !panelSummaries.isEmpty() || !pathogenFindings.isEmpty());
        summary.put("abnormal_items", abnormalItems);
        summary.put("panel_summaries", panelSummaries);
        summary.put("pathogen_findings", deduplicateKeepOrder(pathogenFindings, 8));
        summary.put("trend_findings", deduplicateKeepOrder(trendFindings, 6));
        summary.put("alert_flags", deduplicateKeepOrder(alertFlags, 6));
        return summary;
    }

    private List<Map<String, Object>> summarizeImaging(JsonNode imagingNode) {
        List<Map<String, Object>> summary = new ArrayList<>();
        if (imagingNode == null || !imagingNode.isArray()) {
            return summary;
        }
        for (JsonNode item : imagingNode) {
            String examType = firstNonBlank(
                    item.path("exam_type").asText(""),
                    item.path("check_type").asText(""),
                    item.path("itemname").asText(""),
                    item.path("title").asText("")
            );
            List<String> findings = new ArrayList<>();
            collectTextValues(item.get("findings"), findings);
            collectTextValues(item.get("impression"), findings);
            collectTextValues(item.get("result"), findings);
            if (findings.isEmpty()) {
                String plainText = firstNonBlank(item.path("content").asText(""), item.path("desc").asText(""));
                if (StringUtils.hasText(plainText)) {
                    findings.add(plainText);
                }
            }
            if (!StringUtils.hasText(examType) && findings.isEmpty()) {
                continue;
            }
            Map<String, Object> imaging = new LinkedHashMap<>();
            imaging.put("exam_type", examType);
            imaging.put("key_findings", deduplicateKeepOrder(findings, 3));
            imaging.put("impression_level", "objective");
            summary.add(imaging);
            if (summary.size() >= 8) {
                break;
            }
        }
        return summary;
    }

    private List<Map<String, Object>> buildObjectiveEvents(JsonNode rawRoot) {
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> vitalsEvent = buildSimpleEvent(
                "vital_alert",
                extractFirstTextFromMapList(summarizeVitals(rawRoot.path("vital_signs")), "most_abnormal")
        );
        addEventIfPresent(events, vitalsEvent);
        Map<String, Object> labEvent = buildSimpleEvent(
                "lab_alert",
                extractFirstTextFromMapList(summarizeLabs(rawRoot.path("lab_results")), "alert_flags")
        );
        addEventIfPresent(events, labEvent);
        return events;
    }

    private Map<String, Object> buildFusionReadyFacts(Map<String, Object> standardizedDayFacts,
                                                      PatientRawDataEntity rawData) {
        Map<String, Object> fusionFacts = new LinkedHashMap<>();
        fusionFacts.put("reqno", rawData == null ? "" : defaultIfBlank(rawData.getReqno(), ""));
        fusionFacts.put("date", rawData == null || rawData.getDataDate() == null ? "" : rawData.getDataDate().toString());
        fusionFacts.put("meta", buildFusionMeta(standardizedDayFacts));

        Map<String, Object> clinicalReasoningLayer = new LinkedHashMap<>();
        clinicalReasoningLayer.put("problem_candidates", buildProblemCandidates(standardizedDayFacts));
        clinicalReasoningLayer.put("differential_candidates", buildDifferentialCandidates(standardizedDayFacts));
        clinicalReasoningLayer.put("etiology_candidates", buildEtiologyCandidates(standardizedDayFacts));
        clinicalReasoningLayer.put("risk_candidates", buildDetailedRiskCandidates(standardizedDayFacts));
        clinicalReasoningLayer.put("pending_facts", buildPendingFacts(standardizedDayFacts));
        fusionFacts.put("clinical_reasoning_layer", clinicalReasoningLayer);

        Map<String, Object> objectiveFactLayer = new LinkedHashMap<>();
        objectiveFactLayer.put("diagnosis_facts", standardizedDayFacts.getOrDefault("diagnosis_facts", List.of()));
        objectiveFactLayer.put("vitals_summary", standardizedDayFacts.getOrDefault("vitals_summary", Map.of()));
        objectiveFactLayer.put("lab_summary", standardizedDayFacts.getOrDefault("lab_summary", Map.of()));
        objectiveFactLayer.put("imaging_summary", standardizedDayFacts.getOrDefault("imaging_summary", List.of()));
        objectiveFactLayer.put("orders_summary", standardizedDayFacts.getOrDefault("orders_summary", Map.of()));
        objectiveFactLayer.put("objective_events", standardizedDayFacts.getOrDefault("objective_events", List.of()));
        objectiveFactLayer.put("objective_evidence", buildObjectiveEvidence(standardizedDayFacts));
        objectiveFactLayer.put("action_facts", buildActionFacts(standardizedDayFacts));
        fusionFacts.put("objective_fact_layer", objectiveFactLayer);

        fusionFacts.put("fusion_control_layer", Map.of());
        return fusionFacts;
    }

    private boolean hasNonEmptyNode(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() && node.size() > 0;
    }

    private boolean hasLabData(JsonNode labNode) {
        if (labNode == null || !labNode.isObject()) {
            return false;
        }
        return hasNonEmptyNode(labNode.path("test_panels"))
                || hasNonEmptyNode(labNode.path("microbe_panels"))
                || hasNonEmptyNode(labNode.path("results"));
    }

    private boolean hasDoctorOrderData(JsonNode orderNode) {
        if (orderNode == null || !orderNode.isObject()) {
            return false;
        }
        return hasNonEmptyNode(orderNode.path("long_term"))
                || hasNonEmptyNode(orderNode.path("temporary"))
                || hasNonEmptyNode(orderNode.path("sg"));
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String buildLabResultDisplay(JsonNode result) {
        if (result == null || result.isNull()) {
            return "";
        }
        String display = firstNonBlank(
                result.path("display").asText(""),
                result.path("display_value").asText(""),
                result.path("summary").asText("")
        );
        if (StringUtils.hasText(display)) {
            return display;
        }
        String name = firstNonBlank(
                result.path("item_name").asText(""),
                result.path("name").asText(""),
                result.path("short_name").asText("")
        );
        String value = firstNonBlank(
                result.path("value").asText(""),
                result.path("result").asText(""),
                result.path("report_value").asText("")
        );
        String unit = result.path("unit").asText("");
        String flag = firstNonBlank(
                result.path("flag").asText(""),
                result.path("abnormal_flag").asText(""),
                result.path("remark").asText("")
        );
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(name)) {
            builder.append(name);
        }
        if (StringUtils.hasText(value)) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(value);
        }
        if (StringUtils.hasText(unit)) {
            builder.append(unit.startsWith(" ") ? unit : " " + unit);
        }
        if (StringUtils.hasText(flag)) {
            builder.append(' ').append(flag);
        }
        return builder.toString().trim();
    }

    private boolean isLabResultAbnormal(JsonNode result, String display) {
        if (result != null && !result.isNull()) {
            if (result.path("is_abnormal").asBoolean(false)) {
                return true;
            }
            String abnormalFlag = firstNonBlank(
                    result.path("flag").asText(""),
                    result.path("abnormal_flag").asText(""),
                    result.path("remark").asText("")
            );
            if (containsAny(abnormalFlag, "高", "低", "↑", "↓", "阳性", "异常")) {
                return true;
            }
        }
        return containsAny(display, "高", "低", "↑", "↓", "阳性", "异常", "危急");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private void collectTextValues(JsonNode node, List<String> collector) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText("").trim();
            if (StringUtils.hasText(text)) {
                collector.add(text);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectTextValues(item, collector);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectTextValues(entry.getValue(), collector));
        }
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

    private Map<String, Object> buildSimpleEvent(String eventType, String content) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_type", eventType);
        event.put("content", content);
        return event;
    }

    private void addEventIfPresent(List<Map<String, Object>> events, Map<String, Object> event) {
        if (event == null) {
            return;
        }
        Object content = event.get("content");
        if (content instanceof String text && StringUtils.hasText(text)) {
            events.add(event);
        }
    }

    private String extractFirstTextFromMapList(Map<String, Object> source, String key) {
        if (source == null) {
            return "";
        }
        Object value = source.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first == null ? "" : String.valueOf(first);
        }
        return "";
    }

    private List<Map<String, Object>> buildProblemCandidates(Map<String, Object> standardizedDayFacts) {
        LinkedHashMap<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        Object diagnosisFacts = standardizedDayFacts.get("diagnosis_facts");
        if (diagnosisFacts instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> source)) {
                    continue;
                }
                String name = valueAsString(source.get("name")).trim();
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("problem", name);
                candidate.put("source_types", List.of(defaultIfBlank(valueAsString(source.get("source")), "diagnosis")));
                candidate.put("source_note_refs", List.of());
                mergeProblemCandidate(candidates, candidate);
                if (candidates.size() >= 10) {
                    break;
                }
            }
        }
        Object structured = standardizedDayFacts.get("structured");
        if (structured instanceof List<?> notes) {
            for (Object note : notes) {
                if (!(note instanceof Map<?, ?> noteMap)) {
                    continue;
                }
                String noteType = valueAsString(noteMap.get("note_type"));
                String noteRef = buildNoteRef(noteType, valueAsString(noteMap.get("timestamp")));
                Object structuredNode = noteMap.get("structured");
                JsonNode jsonNode = structuredNode instanceof JsonNode node
                        ? node
                        : objectMapper.valueToTree(structuredNode);
                extractProblemCandidatesFromStructured(jsonNode, noteType, noteRef, candidates);
                if (candidates.size() >= 10) {
                    break;
                }
            }
        }
        return new ArrayList<>(candidates.values()).subList(0, Math.min(candidates.size(), 10));
    }

    private Map<String, Object> buildFusionMeta(Map<String, Object> standardizedDayFacts) {
        Map<String, Object> meta = new LinkedHashMap<>();
        List<Map<String, Object>> notes = readStructuredNotes(standardizedDayFacts);
        meta.put("has_admission_note", notes.stream().anyMatch(note -> containsAny(valueAsString(note.get("note_type")), "首次病程", "入院记录")));
        meta.put("has_progress_note", notes.stream().anyMatch(note -> containsAny(valueAsString(note.get("note_type")), "日常病程")));
        meta.put("has_consultation_note", notes.stream().anyMatch(note -> containsAny(valueAsString(note.get("note_type")), "会诊")));
        meta.put("has_procedure_note", notes.stream().anyMatch(note -> containsAny(valueAsString(note.get("note_type")), "手术")));
        List<String> refs = new ArrayList<>();
        for (Map<String, Object> note : notes) {
            refs.add(buildNoteRef(valueAsString(note.get("note_type")), valueAsString(note.get("timestamp"))));
        }
        meta.put("source_note_refs", deduplicateKeepOrder(refs, 20));
        return meta;
    }

    private List<Map<String, Object>> buildDifferentialCandidates(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : readStructuredNotes(standardizedDayFacts)) {
            String noteType = valueAsString(note.get("note_type"));
            String noteRef = buildNoteRef(noteType, valueAsString(note.get("timestamp")));
            JsonNode node = toStructuredNode(note.get("structured"));
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
                candidate.put("source_types", List.of(noteType));
                candidate.put("source_note_refs", List.of(noteRef));
                result.add(candidate);
            }
        }
        return deduplicateMapList(result, "diagnosis", 10);
    }

    private List<Map<String, Object>> buildEtiologyCandidates(Map<String, Object> standardizedDayFacts) {
        return List.of();
    }

    private List<Map<String, Object>> buildObjectiveEvidence(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : readStructuredNotes(standardizedDayFacts)) {
            String noteType = valueAsString(note.get("note_type"));
            String noteRef = buildNoteRef(noteType, valueAsString(note.get("timestamp")));
            JsonNode node = toStructuredNode(note.get("structured"));
            LinkedHashSet<String> values = new LinkedHashSet<>();
            collectFieldText(node, values, "new_findings", "worsening_points", "improving_points",
                    "key_exam_changes", "key_supporting_evidence", "critical_exam_or_pathology");
            JsonNode keyFindings = node.path("key_findings");
            if (keyFindings.isObject()) {
                flattenText(keyFindings.path("vitals"), values);
                flattenText(keyFindings.path("exam"), values);
                flattenText(keyFindings.path("labs"), values);
                flattenText(keyFindings.path("imaging"), values);
            }
            for (String value : values) {
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fact", value);
                item.put("source_types", List.of(noteType));
                item.put("source_note_refs", List.of(noteRef));
                result.add(item);
            }
        }
        return deduplicateMapList(result, "fact", 24);
    }

    private List<Map<String, Object>> buildActionFacts(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : readStructuredNotes(standardizedDayFacts)) {
            String noteType = valueAsString(note.get("note_type"));
            String noteRef = buildNoteRef(noteType, valueAsString(note.get("timestamp")));
            JsonNode node = toStructuredNode(note.get("structured"));
            LinkedHashSet<String> actions = new LinkedHashSet<>();
            JsonNode plan = node.path("initial_plan");
            if (plan.isObject()) {
                flattenText(plan.path("treatment"), actions);
            }
            collectFieldText(node, actions, "treatment_adjustments", "urgent_actions", "major_actions", "immediate_postop_orders");
            for (String action : actions) {
                if (!StringUtils.hasText(action)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("action", action);
                item.put("source_types", List.of(noteType));
                item.put("source_note_refs", List.of(noteRef));
                result.add(item);
            }
        }
        return deduplicateMapList(result, "action", 16);
    }

    private List<Map<String, Object>> buildPendingFacts(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : readStructuredNotes(standardizedDayFacts)) {
            String noteType = valueAsString(note.get("note_type"));
            String noteRef = buildNoteRef(noteType, valueAsString(note.get("timestamp")));
            JsonNode node = toStructuredNode(note.get("structured"));
            LinkedHashSet<String> items = new LinkedHashSet<>();
            JsonNode plan = node.path("initial_plan");
            if (plan.isObject()) {
                flattenText(plan.path("tests"), items);
                flattenText(plan.path("monitoring"), items);
                flattenText(plan.path("consults"), items);
                flattenText(plan.path("procedures"), items);
                flattenText(plan.path("education"), items);
            }
            collectFieldText(node, items, "next_focus_24h", "short_term_plan_24h");
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
                item.put("source_types", List.of(noteType));
                item.put("source_note_refs", List.of(noteRef));
                result.add(item);
            }
        }
        return deduplicateMapList(result, "item", 16);
    }

    private List<Map<String, Object>> buildDetailedRiskCandidates(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : readStructuredNotes(standardizedDayFacts)) {
            String noteType = valueAsString(note.get("note_type"));
            String noteRef = buildNoteRef(noteType, valueAsString(note.get("timestamp")));
            JsonNode node = toStructuredNode(note.get("structured"));
            JsonNode riskAlerts = node.path("risk_alerts");
            if (riskAlerts.isArray()) {
                for (JsonNode item : riskAlerts) {
                    String risk = item.path("risk").asText("").trim();
                    if (!StringUtils.hasText(risk)) {
                        continue;
                    }
                    Map<String, Object> riskItem = new LinkedHashMap<>();
                    riskItem.put("risk", risk);
                    riskItem.put("source_types", List.of(noteType));
                    riskItem.put("source_note_refs", List.of(noteRef));
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
                            riskItem.put("source_types", List.of(noteType));
                            riskItem.put("source_note_refs", List.of(noteRef));
                            result.add(riskItem);
                        }
                    }
                }
            }
        }
        return deduplicateMapList(result, "risk", 12);
    }

    private void mergeProblemCandidate(Map<String, Map<String, Object>> collector, Map<String, Object> candidate) {
        String key = valueAsString(candidate.get("problem")).trim();
        if (!StringUtils.hasText(key)) {
            return;
        }
        Map<String, Object> existing = collector.get(key);
        if (existing == null) {
            collector.put(key, candidate);
            return;
        }
        String certainty = preferNonBlank(valueAsString(existing.get("certainty")), valueAsString(candidate.get("certainty")));
        String status = preferNonBlank(valueAsString(existing.get("status")), valueAsString(candidate.get("status")));
        LinkedHashSet<String> mergedSources = new LinkedHashSet<>();
        addAllStrings(mergedSources, existing.get("source_types"));
        addAllStrings(mergedSources, candidate.get("source_types"));
        LinkedHashSet<String> mergedSourceRefs = new LinkedHashSet<>();
        addAllStrings(mergedSourceRefs, existing.get("source_note_refs"));
        addAllStrings(mergedSourceRefs, candidate.get("source_note_refs"));
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

    private List<Map<String, Object>> readStructuredNotes(Map<String, Object> standardizedDayFacts) {
        List<Map<String, Object>> notes = new ArrayList<>();
        Object structured = standardizedDayFacts.get("structured");
        if (structured instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    notes.add((Map<String, Object>) map);
                }
            }
        }
        return notes;
    }

    private JsonNode toStructuredNode(Object structuredNode) {
        if (structuredNode instanceof JsonNode node) {
            return node;
        }
        return objectMapper.valueToTree(structuredNode);
    }

    private String buildNoteRef(String noteType, String timestamp) {
        String type = defaultIfBlank(noteType, "");
        String time = defaultIfBlank(timestamp, "");
        return StringUtils.hasText(time) ? type + "@" + time : type;
    }

    private List<Map<String, Object>> deduplicateMapList(List<Map<String, Object>> values, String keyField, int maxSize) {
        LinkedHashMap<String, Map<String, Object>> deduplicated = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            String key = valueAsString(value.get(keyField)).trim();
            if (!StringUtils.hasText(key) || deduplicated.containsKey(key)) {
                continue;
            }
            deduplicated.put(key, value);
            if (deduplicated.size() >= maxSize) {
                break;
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    private void addAllStrings(Collection<String> collector, Object source) {
        if (source instanceof Collection<?> values) {
            for (Object value : values) {
                if (value == null) {
                    continue;
                }
                String text = String.valueOf(value).trim();
                if (StringUtils.hasText(text)) {
                    collector.add(text);
                }
            }
        }
    }

    private void collectFieldText(JsonNode node, Set<String> collector, String... fieldNames) {
        for (String fieldName : fieldNames) {
            flattenText(node.path(fieldName), collector);
        }
    }

    private void flattenText(JsonNode node, Set<String> collector) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (StringUtils.hasText(text)) {
                collector.add(text);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                flattenText(item, collector);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> flattenText(entry.getValue(), collector));
        }
    }

    private List<Map<String, Object>> buildDailyFusionTimelineEntries(PatientRawDataEntity rawData,
                                                                       JsonNode dailyFusionNode) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> timelineEntry = new LinkedHashMap<>();
        timelineEntry.put("time", rawData.getDataDate() == null ? "" : rawData.getDataDate().toString());
        timelineEntry.put("record_type", "daily_fusion");
        timelineEntry.put("day_summary", dailyFusionNode.path("day_summary").asText(""));
        timelineEntry.put("daily_fusion", dailyFusionNode);
        entries.add(timelineEntry);
        return entries;
    }

    private int noteTypePriority(String noteType) {
        IllnessRecordType type = IllnessRecordType.resolve(noteType);
        return switch (type) {
            case FIRST_COURSE, ADMISSION -> 1;
            case SURGERY -> 2;
            case CONSULTATION -> 3;
            case DAILY -> 4;
        };
    }

    private String buildShortExcerpt(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

    private double parseDouble(String text) {
        if (!StringUtils.hasText(text)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private int[] parseBloodPressure(String text) {
        if (!StringUtils.hasText(text) || !text.contains("/")) {
            return null;
        }
        String[] parts = text.split("/");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{(int) Math.round(Double.parseDouble(parts[0].trim())),
                    (int) Math.round(Double.parseDouble(parts[1].trim()))};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String formatNumber(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        if (Math.abs(value - Math.round(value)) < 0.0001D) {
            return String.valueOf((long) Math.round(value));
        }
        return String.format("%.1f", value);
    }

    private List<String> buildVitalMostAbnormal(double maxTemp, double maxPulse, int minSys, int minDia) {
        List<String> result = new ArrayList<>();
        if (maxTemp != Double.NEGATIVE_INFINITY && maxTemp >= 39.0D) {
            result.add("高热 " + formatNumber(maxTemp) + "℃");
        }
        if (maxPulse != Double.NEGATIVE_INFINITY && maxPulse >= 120D) {
            result.add("心动过速 " + formatNumber(maxPulse) + " 次/分");
        }
        if (minSys != Integer.MAX_VALUE && minSys < 90) {
            result.add("低血压 " + minSys + "/" + minDia + " mmHg");
        }
        return result;
    }

    private void collectArrayText(JsonNode node, Collection<String> collector) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String val = item.asText();
            if (StringUtils.hasText(val)) {
                collector.add(val.trim());
            }
        }
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private PromptDefinition selectPromptByItemName(String itemname) {
        return switch (IllnessRecordType.resolve(itemname)) {
            case FIRST_COURSE, ADMISSION -> new PromptDefinition(
                    PromptType.FIRST_ILLNESS_COURSE_NEW,
                    FormatAgentPrompt.FIRST_ILLNESS_COURSE_PROMPT_NEW,
                    ""
            );
            case CONSULTATION -> new PromptDefinition(
                    PromptType.CONSULTATION_ILLNESS_COURSE_NEW,
                    FormatAgentPrompt.CONSULTATION_ILLNESS_COURSE_PROMPT_NEW,
                    ""
            );
            case SURGERY -> new PromptDefinition(
                    PromptType.SURGERY_ILLNESS_COURSE_NEW,
                    FormatAgentPrompt.SURGERY_ILLNESS_COURSE_PROMPT_NEW,
                    ""
            );
            case DAILY -> new PromptDefinition(
                    PromptType.ILLNESS_COURSE_NEW,
                    FormatAgentPrompt.ILLNESS_COURSE_PROMPT_NEW,
                    ""
            );
        };
    }

    private PromptDefinition dailyFusionPromptDefinition() {
        return new PromptDefinition(
                PromptType.DAILY_FUSION,
                FormatAgentPrompt.DAILY_FUSION_SYSTEM_PROMPT,
                FormatAgentPrompt.DAILY_FUSION_USER_PROMPT
        );
    }

    private String trimTimelineForModelInput(String summaryJson, PatientRawDataEntity rawData) {
        if (!StringUtils.hasText(summaryJson) || summaryJson.length() <= MODEL_TIMELINE_MAX_LENGTH) {
            return StringUtils.hasText(summaryJson) ? summaryJson : "{}";
        }
        JsonNode root = parseJsonQuietly(summaryJson);
        if (root == null) {
            return summaryJson.substring(0, MODEL_TIMELINE_MAX_LENGTH);
        }

        long referenceEpoch = resolveReferenceEpoch(rawData);
        JsonNode trimmedNode = root.deepCopy();
        ArrayNode timelineArray = locateTimelineArray(trimmedNode);
        if (timelineArray == null || timelineArray.size() <= 1) {
            String raw = toJsonQuietly(trimmedNode);
            return raw.length() <= MODEL_TIMELINE_MAX_LENGTH ? raw : raw.substring(0, MODEL_TIMELINE_MAX_LENGTH);
        }

        ArrayNode reduced = trimTimelineArray(timelineArray, referenceEpoch, MODEL_TIMELINE_MAX_LENGTH, trimmedNode);
        replaceTimelineArray(trimmedNode, reduced);
        String trimmed = toJsonQuietly(trimmedNode);
        if (trimmed.length() > MODEL_TIMELINE_MAX_LENGTH) {
            ArrayNode onlyFirst = objectMapper.createArrayNode();
            onlyFirst.add(reduced.get(0));
            replaceTimelineArray(trimmedNode, onlyFirst);
            trimmed = toJsonQuietly(trimmedNode);
            if (trimmed.length() > MODEL_TIMELINE_MAX_LENGTH) {
                return trimmed.substring(0, MODEL_TIMELINE_MAX_LENGTH);
            }
        }
        return trimmed;
    }

    private ArrayNode trimTimelineArray(ArrayNode original, long referenceEpoch, int maxLength, JsonNode containerNode) {
        if (original == null || original.size() <= 1) {
            return original;
        }
        JsonNode first = original.get(0);
        List<JsonNode> candidates = new ArrayList<>();
        for (int i = 1; i < original.size(); i++) {
            candidates.add(original.get(i));
        }
        while (!candidates.isEmpty()) {
            ArrayNode current = objectMapper.createArrayNode();
            current.add(first);
            candidates.forEach(current::add);
            replaceTimelineArray(containerNode, current);
            if (toJsonQuietly(containerNode).length() <= maxLength) {
                return current;
            }
            candidates.sort(Comparator.comparingLong(node ->
                    -Math.abs(referenceEpoch - extractTimelineEpoch(node))));
            candidates.remove(0);
        }
        ArrayNode onlyFirst = objectMapper.createArrayNode();
        onlyFirst.add(first);
        return onlyFirst;
    }

    private String appendTimelineEntries(String summaryJson, List<Map<String, Object>> timelineAppendEntries, String reqno) {
        JsonNode root = parseJsonQuietly(summaryJson);
        ObjectNode container;
        ArrayNode timeline;
        if (root == null || root.isNull()) {
            container = objectMapper.createObjectNode();
            timeline = container.putArray("timeline");
        } else if (root.isArray()) {
            container = objectMapper.createObjectNode();
            timeline = container.putArray("timeline");
            root.forEach(timeline::add);
        } else if (root.isObject()) {
            container = (ObjectNode) root.deepCopy();
            timeline = locateTimelineArray(container);
            if (timeline == null) {
                timeline = container.putArray("timeline");
            }
        } else {
            container = objectMapper.createObjectNode();
            timeline = container.putArray("timeline");
        }

        if (StringUtils.hasText(reqno)) {
            container.put("reqno", reqno);
        }
        for (Map<String, Object> entry : timelineAppendEntries) {
            timeline.add(objectMapper.valueToTree(entry));
        }
        return toJsonQuietly(container);
    }

    private ValidatedLlmResult callWithValidation(PromptDefinition promptDefinition, String inputJson) {
        List<AttemptReport> attempts = new ArrayList<>();
        List<ValidationIssue> issues = Collections.emptyList();
        for (int attempt = 1; attempt <= 3; attempt++) {
            String rawOutput = callPrompt(promptDefinition, inputJson, issues);
            ValidationResult validationResult = validatePromptOutput(promptDefinition, rawOutput);
            attempts.add(new AttemptReport(attempt, validationResult.valid(), validationResult.issues()));
            if (validationResult.valid()) {
                return new ValidatedLlmResult(true, validationResult.parsedNode(), attempts, "");
            }
            issues = validationResult.issues();
        }
        String failureReason = issues.isEmpty() ? "LLM output validation failed" : issues.get(0).reason();
        log.warn("Prompt validation failed after retries, promptType={}, reason={}", promptDefinition.type(), failureReason);
        return new ValidatedLlmResult(false, objectMapper.createObjectNode(), attempts, failureReason);
    }

    private String callPrompt(PromptDefinition promptDefinition, String inputJson, List<ValidationIssue> issues) {
        if (!promptDefinition.hasUserPrompt()) {
            String userInput = issues == null || issues.isEmpty()
                    ? inputJson
                    : buildRetryUserInput(inputJson, issues);
            return callWithPrompt(promptDefinition.systemPrompt(), userInput);
        }
        String userPrompt = promptDefinition.userPrompt();
        if (issues != null && !issues.isEmpty()) {
            userPrompt = userPrompt + "\n\n" + buildRetryInstruction(issues);
        }
        return callWithPrompt(promptDefinition.systemPrompt(), userPrompt, inputJson);
    }

    private ValidationResult validatePromptOutput(PromptDefinition promptDefinition, String rawOutput) {
        JsonNode parsedNode = parseJsonQuietly(rawOutput);
        if (parsedNode == null) {
            return new ValidationResult(
                    false,
                    null,
                    List.of(new ValidationIssue(
                            "$",
                            buildShortExcerpt(defaultIfBlank(rawOutput, ""), 200),
                            List.of(),
                            "输出不是合法JSON"
                    ))
            );
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (EnumFieldRule rule : promptDefinition.validationSpec().rules()) {
            validateEnumRule(rule, parsedNode, issues);
        }
        return new ValidationResult(issues.isEmpty(), parsedNode, issues);
    }

    private void validateEnumRule(EnumFieldRule rule, JsonNode root, List<ValidationIssue> issues) {
        String[] segments = rule.jsonPath().split("\\.");
        validateEnumRuleSegments(rule, root, segments, 0, "$", issues);
    }

    private void validateEnumRuleSegments(EnumFieldRule rule,
                                          JsonNode currentNode,
                                          String[] segments,
                                          int index,
                                          String currentPath,
                                          List<ValidationIssue> issues) {
        if (index >= segments.length) {
            validateLeafValue(rule, currentNode, currentPath, issues);
            return;
        }
        String segment = segments[index];
        boolean arraySegment = segment.endsWith("[]");
        String fieldName = arraySegment ? segment.substring(0, segment.length() - 2) : segment;
        String nextPath = currentPath + "." + fieldName;
        JsonNode nextNode = currentNode == null ? null : currentNode.get(fieldName);
        if (nextNode == null || nextNode.isMissingNode() || nextNode.isNull()) {
            if (rule.required()) {
                issues.add(new ValidationIssue(nextPath, "", new ArrayList<>(rule.allowedValues()), "字段缺失"));
            }
            return;
        }
        if (arraySegment) {
            if (!nextNode.isArray()) {
                issues.add(new ValidationIssue(nextPath, buildShortExcerpt(nextNode.toString(), 80),
                        new ArrayList<>(rule.allowedValues()), "字段不是数组"));
                return;
            }
            for (int i = 0; i < nextNode.size(); i++) {
                validateEnumRuleSegments(rule, nextNode.get(i), segments, index + 1, nextPath + "[" + i + "]", issues);
            }
            return;
        }
        validateEnumRuleSegments(rule, nextNode, segments, index + 1, nextPath, issues);
    }

    private void validateLeafValue(EnumFieldRule rule, JsonNode valueNode, String jsonPath, List<ValidationIssue> issues) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            if (rule.required()) {
                issues.add(new ValidationIssue(jsonPath, "", new ArrayList<>(rule.allowedValues()), "字段缺失"));
            }
            return;
        }
        if (!valueNode.isTextual()) {
            issues.add(new ValidationIssue(
                    jsonPath,
                    buildShortExcerpt(valueNode.toString(), 80),
                    new ArrayList<>(rule.allowedValues()),
                    "字段必须是字符串枚举"
            ));
            return;
        }
        String value = valueNode.asText("").trim();
        if (!StringUtils.hasText(value) && rule.required()) {
            issues.add(new ValidationIssue(jsonPath, value, new ArrayList<>(rule.allowedValues()), "字段为空"));
            return;
        }
        if (StringUtils.hasText(value) && !rule.allowedValues().contains(value)) {
            issues.add(new ValidationIssue(jsonPath, value, new ArrayList<>(rule.allowedValues()), "字段值不在允许枚举中"));
        }
    }

    private String buildRetryUserInput(String inputJson, List<ValidationIssue> issues) {
        return buildRetryInstruction(issues) + "\n【输入数据】\n" + inputJson;
    }

    private String buildRetryInstruction(List<ValidationIssue> issues) {
        StringBuilder builder = new StringBuilder();
        builder.append("上一次输出未通过校验，请仅修正以下问题，并重新输出完整JSON：\n");
        for (int i = 0; i < issues.size(); i++) {
            ValidationIssue issue = issues.get(i);
            builder.append(i + 1).append(". 路径 ").append(issue.jsonPath());
            if (StringUtils.hasText(issue.invalidValue())) {
                builder.append(" 的值为 ").append(issue.invalidValue());
            }
            if (!issue.allowedValues().isEmpty()) {
                builder.append("，允许值仅为 ").append(String.join("|", issue.allowedValues()));
            }
            builder.append("。原因：").append(issue.reason()).append('\n');
        }
        builder.append("不要输出解释，只输出修正后的完整JSON。");
        return builder.toString();
    }

    private Map<String, Object> failureReport(String reason) {
        return Map.of(
                "success", false,
                "attempt_count", 0,
                "failure_reason", reason,
                "attempts", List.of()
        );
    }

    private JsonNode parseJsonQuietly(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toJsonQuietly(JsonNode node) {
        if (node == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ArrayNode locateTimelineArray(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return (ArrayNode) root;
        }
        if (root.isObject()) {
            JsonNode timelineNode = root.get("timeline");
            if (timelineNode != null && timelineNode.isArray()) {
                return (ArrayNode) timelineNode;
            }
        }
        return null;
    }

    private void replaceTimelineArray(JsonNode root, ArrayNode newTimeline) {
        if (root == null || newTimeline == null) {
            return;
        }
        if (root.isObject()) {
            ((ObjectNode) root).set("timeline", newTimeline);
            return;
        }
        if (root.isArray()) {
            ArrayNode arr = (ArrayNode) root;
            arr.removeAll();
            newTimeline.forEach(arr::add);
        }
    }

    private long resolveReferenceEpoch(PatientRawDataEntity rawData) {
        if (rawData != null && rawData.getDataDate() != null) {
            return rawData.getDataDate().atTime(LocalTime.MIN)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    private long extractTimelineEpoch(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Long.MIN_VALUE;
        }
        List<String> keys = List.of("time", "dataDate", "date", "updateTime");
        for (String key : keys) {
            JsonNode valueNode = node.get(key);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            long epoch = parseToEpoch(valueNode.asText());
            if (epoch != Long.MIN_VALUE) {
                return epoch;
            }
        }
        return Long.MIN_VALUE;
    }

    private long parseToEpoch(String text) {
        if (!StringUtils.hasText(text)) {
            return Long.MIN_VALUE;
        }
        String value = text.trim();
        List<DateTimeFormatter> dateTimeFormatters = DateTimeUtils.DATE_TIME_PARSE_FORMATTERS;
        for (DateTimeFormatter formatter : dateTimeFormatters) {
            try {
                return LocalDateTime.parse(value, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atTime(LocalTime.MIN)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private String resolveIllnessTime(PatientCourseData.PatIllnessCourse illnessCourse, PatientRawDataEntity rawData) {
        LocalDateTime dt = illnessCourse.getChangetime();
        if (dt == null) {
            dt = illnessCourse.getCreattime();
        }
        if (dt == null && rawData.getDataDate() != null) {
            dt = rawData.getDataDate().atTime(LocalTime.MIN);
        }
        return dt == null ? "" : DateTimeUtils.truncateToMillis(dt).format(ILLNESS_TIME_FORMATTER);
    }

    private String callWithPrompt(String systemPrompt, String userPrompt, String inputJson) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt + inputJson)
        ));
        ChatResponse response = super.callModelByPrompt(prompt);
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }

    private String callWithPrompt(String userPrompt, String inputJson) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(userPrompt),
                new UserMessage(inputJson)
        ));
        ChatResponse response = super.callModelByPrompt(prompt);
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }

    private static PromptValidationSpec validationSpecFor(PromptType promptType) {
        return switch (promptType) {
            case FIRST_ILLNESS_COURSE_NEW -> new PromptValidationSpec(List.of(
                    new EnumFieldRule("core_problems[].certainty", ALLOWED_BASELINE_CERTAINTY, true),
                    new EnumFieldRule("core_problems[].time_status", ALLOWED_BASELINE_TIME_STATUS, true),
                    new EnumFieldRule("core_problems[].severity", ALLOWED_SEVERITY, true),
                    new EnumFieldRule("differential_diagnosis[].certainty", ALLOWED_DIFFERENTIAL_CERTAINTY, true)
            ));
            case ILLNESS_COURSE_NEW -> new PromptValidationSpec(List.of(
                    new EnumFieldRule("risk_alerts[].certainty", ALLOWED_RISK_ALERT_CERTAINTY, true)
            ));
            case CONSULTATION_ILLNESS_COURSE_NEW -> new PromptValidationSpec(List.of(
                    new EnumFieldRule("consult_core_judgment[].certainty", ALLOWED_CONSULT_CERTAINTY, true),
                    new EnumFieldRule("risk_alerts[].certainty", ALLOWED_RISK_ALERT_CERTAINTY, true)
            ));
            case SURGERY_ILLNESS_COURSE_NEW -> new PromptValidationSpec(List.of());
            case DAILY_FUSION -> new PromptValidationSpec(List.of(
                    new EnumFieldRule("daily_fusion.problem_list[].problem_type", ALLOWED_PROBLEM_TYPE, true),
                    new EnumFieldRule("daily_fusion.problem_list[].priority", ALLOWED_PRIORITY, true),
                    new EnumFieldRule("daily_fusion.problem_list[].status", ALLOWED_STATUS, true),
                    new EnumFieldRule("daily_fusion.problem_list[].certainty", ALLOWED_CERTAINTY, true)
            ));
        };
    }

    private enum PromptType {
        FIRST_ILLNESS_COURSE_NEW,
        ILLNESS_COURSE_NEW,
        CONSULTATION_ILLNESS_COURSE_NEW,
        SURGERY_ILLNESS_COURSE_NEW,
        DAILY_FUSION
    }

    private record PromptDefinition(PromptType type, String systemPrompt, String userPrompt) {

        private boolean hasUserPrompt() {
            return StringUtils.hasText(userPrompt);
        }

        private PromptValidationSpec validationSpec() {
            return validationSpecFor(type);
        }
    }

    private record PromptValidationSpec(List<EnumFieldRule> rules) {
    }

    private record EnumFieldRule(String jsonPath, Set<String> allowedValues, boolean required) {
    }

    private record ValidationIssue(String jsonPath, String invalidValue, List<String> allowedValues, String reason) {

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("json_path", jsonPath);
            result.put("invalid_value", invalidValue);
            result.put("allowed_values", allowedValues);
            result.put("reason", reason);
            return result;
        }
    }

    private record ValidationResult(boolean valid, JsonNode parsedNode, List<ValidationIssue> issues) {
    }

    private record AttemptReport(int attempt, boolean valid, List<ValidationIssue> issues) {

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("attempt", attempt);
            result.put("valid", valid);
            List<Map<String, Object>> issueMaps = new ArrayList<>();
            for (ValidationIssue issue : issues) {
                issueMaps.add(issue.toMap());
            }
            result.put("issues", issueMaps);
            return result;
        }
    }

    private record ValidatedLlmResult(boolean success, JsonNode outputNode, List<AttemptReport> attempts, String failureReason) {

        private Map<String, Object> toReport() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            result.put("attempt_count", attempts.size());
            result.put("failure_reason", StringUtils.hasText(failureReason) ? failureReason : "");
            List<Map<String, Object>> attemptMaps = new ArrayList<>();
            for (AttemptReport attempt : attempts) {
                attemptMaps.add(attempt.toMap());
            }
            result.put("attempts", attemptMaps);
            return result;
        }
    }

    public record DailyIllnessResult(String structDataJson, String updatedSummaryJson) {
    }
}
