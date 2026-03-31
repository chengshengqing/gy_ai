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

    private static final int MODEL_TIMELINE_MAX_LENGTH = 6000;
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

    private final ObjectMapper objectMapper;

    public SummaryAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    public String extractDailyEvents(PatientRawDataEntity rawDataEntity, PatientSummaryEntity latestSummary) {
        String input = StringUtils.hasText(rawDataEntity.getDataJson()) ? rawDataEntity.getDataJson() : "{}";
        Map<String, String> splitInput = AgentUtils.splitInput(input);

        return null;
    }

    private String normalizeJson(String output) {
        if (!StringUtils.hasText(output)) {
            return "{}";
        }
        String trimmed = output.trim();
        try {
            objectMapper.readTree(trimmed);
            return trimmed;
        } catch (Exception ignored) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String candidate = trimmed.substring(start, end + 1);
                try {
                    objectMapper.readTree(candidate);
                    return candidate;
                } catch (Exception e) {
                    log.warn("SummaryAgent返回非JSON，保留原文");
                }
            }
            return trimmed;
        }
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
        Set<String> sourceRefs = new LinkedHashSet<>();

        for (PatientCourseData.PatIllnessCourse illnessCourse : illnessCourseList) {
            String itemname = illnessCourse.getItemname();
            String illnesscontent = illnessCourse.getIllnesscontent();
            if (!StringUtils.hasText(illnesscontent)) {
                continue;
            }
            String promptTemplate = selectPromptByItemName(itemname);

            Map<String, Object> currentIllness = new LinkedHashMap<>();
            currentIllness.put("itemname", itemname);
            currentIllness.put("time", resolveIllnessTime(illnessCourse, rawData));
            currentIllness.put("illnesscontent", illnesscontent);
            String llmResult = callWithPrompt(promptTemplate, AgentUtils.toJson(currentIllness));
            JsonNode normalizedResultNode = toJsonNodeOrText(llmResult);
            String noteTime = resolveIllnessTime(illnessCourse, rawData);

            Map<String, Object> noteStructured = new LinkedHashMap<>();
            noteStructured.put("note_type", itemname);
            noteStructured.put("timestamp", noteTime);
            noteStructured.put("structured", normalizedResultNode);
            noteStructuredList.add(noteStructured);
            sourceRefs.add(itemname + "@" + noteTime);
        }

        noteStructuredList.sort(Comparator.comparingInt(item -> noteTypePriority(String.valueOf(item.get("note_type")))));
        Map<String, Object> dayContext = buildStandardizedDayFacts(root, noteStructuredList);

        JsonNode boundedDailyFusionNode = objectMapper.createObjectNode();
        if (canGenerateDailyFusion(dayContext)) {
            Map<String, Object> fusionReadyFactsInput = buildFusionReadyFacts(dayContext, rawData);
            JsonNode fusionReadyFactsNode = objectMapper.valueToTree(fusionReadyFactsInput);
            String userInput = AgentUtils.toJson(Map.of("fusion_ready_facts", fusionReadyFactsNode));
            dayContext.put("llm_input", userInput);
            String dailyFusionRaw = callWithPrompt(
                    FormatAgentPrompt.DAILY_FUSION_SYSTEM_PROMPT,
                    FormatAgentPrompt.DAILY_FUSION_USER_PROMPT,
                    userInput
            );
            JsonNode dailyFusionNode = toJsonNodeOrText(dailyFusionRaw);
            boundedDailyFusionNode = applyClinicalBoundaryRules(dailyFusionNode, root, fusionReadyFactsNode, noteStructuredList);
        }

        Map<String, Object> structData = new LinkedHashMap<>();
        structData.put("day_context", dayContext);
        structData.put("daily_fusion", boundedDailyFusionNode);

        String structDataJson = AgentUtils.toJson(structData);
        List<Map<String, Object>> timelineAppendEntries = boundedDailyFusionNode.isObject() && boundedDailyFusionNode.size() > 0
                ? buildDailyFusionTimelineEntries(rawData, boundedDailyFusionNode, sourceRefs)
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
        Map<String, Set<String>> buckets = new LinkedHashMap<>();
        buckets.put("抗感染", new LinkedHashSet<>());
        buckets.put("利尿", new LinkedHashSet<>());
        buckets.put("吸氧", new LinkedHashSet<>());
        buckets.put("退热", new LinkedHashSet<>());
        buckets.put("会诊", new LinkedHashSet<>());
        buckets.put("手术准备", new LinkedHashSet<>());
        buckets.put("补液", new LinkedHashSet<>());

        List<String> allOrders = new ArrayList<>();
        if (orderNode != null && orderNode.isObject()) {
            collectArrayText(orderNode.get("long_term"), allOrders);
            collectArrayText(orderNode.get("temporary"), allOrders);
            collectArrayText(orderNode.get("sg"), allOrders);
        }
        for (String order : allOrders) {
            String val = order == null ? "" : order.trim();
            if (!StringUtils.hasText(val)) {
                continue;
            }
            if (containsAny(val, "抗", "头孢", "青霉素", "哌拉", "美罗", "感染")) {
                buckets.get("抗感染").add(val);
            }
            if (containsAny(val, "利尿", "呋塞米", "托拉塞米", "螺内酯")) {
                buckets.get("利尿").add(val);
            }
            if (containsAny(val, "吸氧", "氧疗", "鼻导管", "高流量", "呼吸机")) {
                buckets.get("吸氧").add(val);
            }
            if (containsAny(val, "退热", "物理降温", "冰敷", "布洛芬", "对乙酰氨基酚")) {
                buckets.get("退热").add(val);
            }
            if (containsAny(val, "会诊", "请", "协助诊治")) {
                buckets.get("会诊").add(val);
            }
            if (containsAny(val, "术前", "备皮", "禁食", "麻醉", "手术")) {
                buckets.get("手术准备").add(val);
            }
            if (containsAny(val, "补液", "输液", "补盐", "补钾")) {
                buckets.get("补液").add(val);
            }
        }

        List<Map<String, Object>> actionCategories = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : buckets.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", entry.getKey());
            item.put("actions", new ArrayList<>(entry.getValue()));
            actionCategories.add(item);
        }
        result.put("action_categories", actionCategories);
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
            fact.put("certainty", inferDiagnosisCertainty(diagnosis));
            fact.put("time_status", inferDiagnosisTimeStatus(diagnosis, noteStructuredList));
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
        clinicalReasoningLayer.put("diagnostic_pending", buildDiagnosticPending(standardizedDayFacts));
        clinicalReasoningLayer.put("recheck_pending", buildRecheckPending(standardizedDayFacts));
        clinicalReasoningLayer.put("process_pending", buildProcessPending(standardizedDayFacts));
        fusionFacts.put("clinical_reasoning_layer", clinicalReasoningLayer);

        Map<String, Object> objectiveFactLayer = new LinkedHashMap<>();
        objectiveFactLayer.put("diagnosis_facts", standardizedDayFacts.getOrDefault("diagnosis_facts", List.of()));
        objectiveFactLayer.put("vitals_summary", standardizedDayFacts.getOrDefault("vitals_summary", Map.of()));
        objectiveFactLayer.put("lab_summary", standardizedDayFacts.getOrDefault("lab_summary", Map.of()));
        objectiveFactLayer.put("imaging_summary", standardizedDayFacts.getOrDefault("imaging_summary", List.of()));
        objectiveFactLayer.put("orders_summary", standardizedDayFacts.getOrDefault("orders_summary", Map.of()));
        objectiveFactLayer.put("objective_events", standardizedDayFacts.getOrDefault("objective_events", List.of()));
        objectiveFactLayer.put("objective_evidence", buildObjectiveEvidence(standardizedDayFacts));
        objectiveFactLayer.put("treatment_actions", buildTreatmentActions(standardizedDayFacts));
        objectiveFactLayer.put("diagnostic_actions", buildDiagnosticActions(standardizedDayFacts));
        objectiveFactLayer.put("monitoring_actions", buildMonitoringActions(standardizedDayFacts));
        objectiveFactLayer.put("process_actions", buildProcessActions(standardizedDayFacts));
        fusionFacts.put("objective_fact_layer", objectiveFactLayer);

        Map<String, Object> fusionControlLayer = new LinkedHashMap<>();
        fusionControlLayer.put("fusion_hints", buildFusionHints());
        fusionFacts.put("fusion_control_layer", fusionControlLayer);
        return fusionFacts;
    }

    private JsonNode normalizeFusionReadyFacts(JsonNode modelNode, Map<String, Object> fallbackFacts) {
        ObjectNode normalized = objectMapper.createObjectNode();
        JsonNode source = modelNode != null && modelNode.isObject() ? modelNode : objectMapper.valueToTree(fallbackFacts);
        normalized.set("problem_candidates", ensureArrayNode(source.get("problem_candidates")));
        normalized.set("evidence_candidates", ensureArrayNode(source.get("evidence_candidates")));
        normalized.set("action_candidates", ensureArrayNode(source.get("action_candidates")));
        normalized.set("risk_candidates", ensureArrayNode(source.get("risk_candidates")));
        normalized.set("pending_candidates", ensureArrayNode(source.get("pending_candidates")));
        return normalized;
    }

    private Map<String, Object> buildUnifiedIntermediate(String noteType, String timestamp, JsonNode summaryNode) {
        Map<String, Object> unified = new LinkedHashMap<>();
        unified.put("note_type", noteType);
        unified.put("timestamp", timestamp);
        unified.put("importance", noteTypePriority(noteType) <= 2 ? "high" : "medium");
        unified.put("problems", extractProblemTexts(summaryNode));
        unified.put("findings", extractFindingTexts(summaryNode));
        unified.put("plans", extractPlanTexts(summaryNode));
        unified.put("risks", extractRiskTexts(summaryNode));
        return unified;
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

    private String inferDiagnosisCertainty(String diagnosis) {
        if (!StringUtils.hasText(diagnosis)) {
            return "confirmed";
        }
        if (containsAny(diagnosis, "待排", "待查", "进一步检查")) {
            return "workup_needed";
        }
        if (containsAny(diagnosis, "疑似", "可能", "考虑")) {
            return "suspected";
        }
        if (containsAny(diagnosis, "风险", "高危")) {
            return "risk_only";
        }
        return "confirmed";
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String inferDiagnosisTimeStatus(String diagnosis, List<Map<String, Object>> noteStructuredList) {
        StringBuilder contextBuilder = new StringBuilder(diagnosis == null ? "" : diagnosis);
        if (noteStructuredList != null) {
            for (Map<String, Object> note : noteStructuredList) {
                Object structured = note.get("structured");
                if (structured != null) {
                    contextBuilder.append(' ').append(structured);
                }
            }
        }
        String merged = contextBuilder.toString();
        if (containsAny(merged, "急性加重", "加重", "恶化", "再发")) {
            return "acute_exacerbation";
        }
        if (containsAny(merged, "既往", "慢性", "长期", "病史", "多年", "反复", "高血压", "糖尿病", "COPD", "房颤", "心衰")) {
            return "chronic";
        }
        if (containsAny(merged, "首次发现", "新发", "新出现")) {
            return "newly_identified";
        }
        return "clarified";
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
                candidate.put("problem_key", toProblemKey(name));
                candidate.put("problem_type", inferProblemType(name));
                candidate.put("certainty", mapFusionCertainty(defaultIfBlank(valueAsString(source.get("certainty")), "confirmed")));
                candidate.put("status", mapFusionStatus(defaultIfBlank(valueAsString(source.get("time_status")), "clarified")));
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

    private List<String> buildEvidenceCandidates(Map<String, Object> standardizedDayFacts) {
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        Object vitals = standardizedDayFacts.get("vitals_summary");
        if (vitals instanceof Map<?, ?> vitalsMap) {
            addAllStrings(evidence, vitalsMap.get("min_max"));
            addAllStrings(evidence, vitalsMap.get("most_abnormal"));
        }
        Object labs = standardizedDayFacts.get("lab_summary");
        if (labs instanceof Map<?, ?> labMap) {
            addAllStrings(evidence, labMap.get("abnormal_items"));
            addAllStrings(evidence, labMap.get("pathogen_findings"));
            addAllStrings(evidence, labMap.get("alert_flags"));
        }
        Object imaging = standardizedDayFacts.get("imaging_summary");
        if (imaging instanceof List<?> imagingList) {
            for (Object item : imagingList) {
                if (!(item instanceof Map<?, ?> imageMap)) {
                    continue;
                }
                String examType = valueAsString(imageMap.get("exam_type")).trim();
                List<String> findings = new ArrayList<>();
                addAllStrings(findings, imageMap.get("key_findings"));
                if (!findings.isEmpty()) {
                    evidence.add((StringUtils.hasText(examType) ? examType + "：" : "") + findings.get(0));
                }
                /*if (evidence.size() >= 10) {
                    break;
                }*/
            }
        }
        return new ArrayList<>(evidence).subList(0, Math.min(evidence.size(), 10));
    }

    private List<String> buildActionCandidates(Map<String, Object> standardizedDayFacts) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        Object orders = standardizedDayFacts.get("orders_summary");
        if (orders instanceof Map<?, ?> orderMap) {
            Object categories = orderMap.get("action_categories");
            if (categories instanceof List<?> categoryList) {
                for (Object category : categoryList) {
                    if (!(category instanceof Map<?, ?> categoryMap)) {
                        continue;
                    }
                    String categoryName = valueAsString(categoryMap.get("category")).trim();
                    List<String> groupedActions = new ArrayList<>();
                    addAllStrings(groupedActions, categoryMap.get("actions"));
                    if (groupedActions.isEmpty()) {
                        continue;
                    }
                    actions.add((StringUtils.hasText(categoryName) ? categoryName + "：" : "") + groupedActions.get(0));
                    if (actions.size() >= 10) {
                        break;
                    }
                }
            }
        }
        Object structured = standardizedDayFacts.get("structured");
        if (structured instanceof List<?> notes) {
            for (Object note : notes) {
                if (!(note instanceof Map<?, ?> noteMap)) {
                    continue;
                }
                Object structuredNode = noteMap.get("structured");
                JsonNode jsonNode = structuredNode instanceof JsonNode node
                        ? node
                        : objectMapper.valueToTree(structuredNode);
                collectFieldText(jsonNode, actions, "treatment_adjustments", "urgent_actions",
                        "immediate_postop_orders", "major_actions");
                JsonNode initialPlan = jsonNode.path("initial_plan");
                if (initialPlan.isObject()) {
                    collectFieldText(initialPlan, actions, "tests", "treatment", "monitoring", "consults", "procedures");
                }
                if (actions.size() >= 10) {
                    break;
                }
            }
        }
        return new ArrayList<>(actions);
    }

    private List<String> buildRiskCandidates(Map<String, Object> standardizedDayFacts) {
        LinkedHashSet<String> risks = new LinkedHashSet<>();
        Object vitals = standardizedDayFacts.get("vitals_summary");
        if (vitals instanceof Map<?, ?> vitalsMap && Boolean.TRUE.equals(vitalsMap.get("need_alert"))) {
            addAllStrings(risks, vitalsMap.get("most_abnormal"));
        }
        Object labs = standardizedDayFacts.get("lab_summary");
        if (labs instanceof Map<?, ?> labMap) {
            addAllStrings(risks, labMap.get("alert_flags"));
        }
        Object structured = standardizedDayFacts.get("structured");
        if (structured instanceof List<?> notes) {
            for (Object note : notes) {
                if (!(note instanceof Map<?, ?> noteMap)) {
                    continue;
                }
                Object structuredNode = noteMap.get("structured");
                JsonNode jsonNode = structuredNode instanceof JsonNode node
                        ? node
                        : objectMapper.valueToTree(structuredNode);
                collectRiskTexts(jsonNode, risks);
                if (risks.size() >= 8) {
                    break;
                }
            }
        }
        return new ArrayList<>(risks).subList(0, Math.min(risks.size(), 8));
    }

    private List<String> buildPendingCandidates(Map<String, Object> standardizedDayFacts) {
        LinkedHashSet<String> pending = new LinkedHashSet<>();
        Object diagnosisFacts = standardizedDayFacts.get("diagnosis_facts");
        if (diagnosisFacts instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> diagnosisMap)) {
                    continue;
                }
                String certainty = valueAsString(diagnosisMap.get("certainty"));
                String name = valueAsString(diagnosisMap.get("name"));
                if (containsAny(certainty, "suspected", "workup_needed") && StringUtils.hasText(name)) {
                    pending.add(name);
                }
            }
        }
        Object structured = standardizedDayFacts.get("structured");
        if (structured instanceof List<?> notes) {
            for (Object note : notes) {
                if (!(note instanceof Map<?, ?> noteMap)) {
                    continue;
                }
                Object structuredNode = noteMap.get("structured");
                if (structuredNode instanceof JsonNode jsonNode) {
                    collectPendingTexts(jsonNode, pending);
                } else if (structuredNode != null) {
                    JsonNode jsonNode = objectMapper.valueToTree(structuredNode);
                    collectPendingTexts(jsonNode, pending);
                }
                if (pending.size() >= 8) {
                    break;
                }
            }
        }
        return new ArrayList<>(pending);
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
                candidate.put("certainty", mapFusionCertainty(defaultIfBlank(item.path("certainty").asText(""), "workup_needed")));
                candidate.put("reason", item.path("reason").asText(""));
                candidate.put("source_types", List.of(noteType));
                candidate.put("source_note_refs", List.of(noteRef));
                result.add(candidate);
            }
        }
        return deduplicateMapList(result, "diagnosis", 10);
    }

    private List<Map<String, Object>> buildEtiologyCandidates(Map<String, Object> standardizedDayFacts) {
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
                if (!containsAny(diagnosis, "冠心病", "瓣膜病", "病因")) {
                    continue;
                }
                Map<String, Object> etiology = new LinkedHashMap<>();
                etiology.put("etiology", diagnosis);
                etiology.put("target_problem_key", inferEtiologyTargetProblemKey(diagnosis));
                etiology.put("certainty", "possible");
                etiology.put("source_types", List.of(noteType));
                etiology.put("source_note_refs", List.of(noteRef));
                result.add(etiology);
            }
        }
        return deduplicateMapList(result, "etiology", 8);
    }

    private Map<String, Object> buildObjectiveEvidence(Map<String, Object> standardizedDayFacts) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("symptoms_signs", buildEvidenceBucket(standardizedDayFacts, EvidenceBucket.SYMPTOMS_SIGNS));
        evidence.put("labs", buildEvidenceBucket(standardizedDayFacts, EvidenceBucket.LABS));
        evidence.put("imaging", buildEvidenceBucket(standardizedDayFacts, EvidenceBucket.IMAGING));
        evidence.put("other_tests", buildEvidenceBucket(standardizedDayFacts, EvidenceBucket.OTHER_TESTS));
        return evidence;
    }

    private List<Map<String, Object>> buildTreatmentActions(Map<String, Object> standardizedDayFacts) {
        return buildDetailedActions(standardizedDayFacts, ActionBucket.TREATMENT);
    }

    private List<Map<String, Object>> buildDiagnosticActions(Map<String, Object> standardizedDayFacts) {
        return buildDetailedActions(standardizedDayFacts, ActionBucket.DIAGNOSTIC);
    }

    private List<Map<String, Object>> buildMonitoringActions(Map<String, Object> standardizedDayFacts) {
        return buildDetailedActions(standardizedDayFacts, ActionBucket.MONITORING);
    }

    private List<Map<String, Object>> buildProcessActions(Map<String, Object> standardizedDayFacts) {
        return buildDetailedActions(standardizedDayFacts, ActionBucket.PROCESS);
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
                    riskItem.put("related_problem_keys", inferProblemKeysFromText(risk + " " + item.path("basis").asText("")));
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
                            riskItem.put("related_problem_keys", inferProblemKeysFromText(item.path("problem").asText("")));
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

    private List<Map<String, Object>> buildDiagnosticPending(Map<String, Object> standardizedDayFacts) {
        return buildPendingItems(standardizedDayFacts, PendingBucket.DIAGNOSTIC);
    }

    private List<Map<String, Object>> buildRecheckPending(Map<String, Object> standardizedDayFacts) {
        return buildPendingItems(standardizedDayFacts, PendingBucket.RECHECK);
    }

    private List<Map<String, Object>> buildProcessPending(Map<String, Object> standardizedDayFacts) {
        return buildPendingItems(standardizedDayFacts, PendingBucket.PROCESS);
    }

    private Map<String, Object> buildFusionHints() {
        Map<String, Object> control = new LinkedHashMap<>();

        control.put("source_priority", Map.of(
                "baseline", List.of("首次病程记录", "入院记录", "手术记录"),
                "update", List.of("日常病程记录", "会诊记录", "术后记录")
        ));

        control.put("data_priority", Map.of(
                "highest", List.of("objective_events", "lab_summary", "vitals_summary"),
                "medium", List.of("orders_summary", "objective_evidence"),
                "lowest", List.of("diagnosis_facts")
        ));

        control.put("problem_selection_rules", Map.of(
                "max_problems", 4,
                "min_problems", 2,
                "exclude_types", List.of("differential", "etiology_only")
        ));

        control.put("deduplication_rules", Map.of(
                "merge_similar_problems", true,
                "merge_similar_actions", true
        ));

        control.put("risk_control_rules", Map.of(
                "max_risks", 5,
                "forbid_risk_to_diagnosis", true
        ));

        control.put("output_constraints", Map.of(
                "strict_json", true,
                "max_key_evidence", 6,
                "max_actions", 6
        ));

        control.put("display_strategy", Map.of(
                "focus_order", List.of("problem", "evidence", "action", "risk", "next_step")
        ));
        return control;
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
        String certainty = preferCertainty(valueAsString(existing.get("certainty")), valueAsString(candidate.get("certainty")));
        String status = preferStatus(valueAsString(existing.get("status")), valueAsString(candidate.get("status")));
        LinkedHashSet<String> mergedSources = new LinkedHashSet<>();
        addAllStrings(mergedSources, existing.get("source_types"));
        addAllStrings(mergedSources, candidate.get("source_types"));
        LinkedHashSet<String> mergedSourceRefs = new LinkedHashSet<>();
        addAllStrings(mergedSourceRefs, existing.get("source_note_refs"));
        addAllStrings(mergedSourceRefs, candidate.get("source_note_refs"));
        existing.put("certainty", certainty);
        existing.put("status", status);
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
                candidate.put("problem_key", toProblemKey(item.path("problem").asText("")));
                candidate.put("problem_type", inferProblemType(item.path("problem").asText("")));
                candidate.put("certainty", mapFusionCertainty(defaultIfBlank(item.path("certainty").asText(""), "confirmed")));
                candidate.put("status", mapFusionStatus(defaultIfBlank(item.path("time_status").asText(""), "clarified")));
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
                candidate.put("problem_key", toProblemKey(item.path("judgment").asText("")));
                candidate.put("problem_type", inferProblemType(item.path("judgment").asText("")));
                candidate.put("certainty", mapFusionCertainty(defaultIfBlank(item.path("certainty").asText(""), "workup_needed")));
                candidate.put("status", "active");
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
                candidate.put("problem_key", toProblemKey(item.path("diagnosis").asText("")));
                candidate.put("problem_type", "differential");
                candidate.put("certainty", mapFusionCertainty(defaultIfBlank(item.path("certainty").asText(""), "workup_needed")));
                candidate.put("status", "active");
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
                candidate.put("problem_key", toProblemKey(item.asText("")));
                candidate.put("problem_type", inferProblemType(item.asText("")));
                candidate.put("certainty", "confirmed");
                candidate.put("status", "active");
                candidate.put("source_types", List.of(noteType));
                candidate.put("source_note_refs", List.of(noteRef));
                mergeProblemCandidate(collector, candidate);
            }
        }
    }

    private void collectRiskTexts(JsonNode node, Set<String> collector) {
        collectFieldText(node, collector, "risk_flags", "postop_risk_alerts");
        JsonNode riskAlerts = node.path("risk_alerts");
        if (riskAlerts.isArray()) {
            for (JsonNode item : riskAlerts) {
                String risk = item.path("risk").asText("");
                if (StringUtils.hasText(risk)) {
                    collector.add(risk.trim());
                }
            }
        }
        JsonNode coreProblems = node.path("core_problems");
        if (coreProblems.isArray()) {
            for (JsonNode item : coreProblems) {
                flattenText(item.path("risk"), collector);
            }
        }
    }

    private String preferCertainty(String left, String right) {
        List<String> order = List.of("confirmed", "suspected", "possible", "workup_needed", "risk_only");
        int leftIndex = order.indexOf(defaultIfBlank(normalizeCertaintyValue(left), "risk_only"));
        int rightIndex = order.indexOf(defaultIfBlank(normalizeCertaintyValue(right), "risk_only"));
        if (leftIndex == -1) {
            return right;
        }
        if (rightIndex == -1) {
            return left;
        }
        return leftIndex <= rightIndex ? left : right;
    }

    private String preferStatus(String left, String right) {
        List<String> order = List.of("acute_exacerbation", "worsening", "active", "chronic", "clarified", "stable", "improving", "unclear");
        int leftIndex = order.indexOf(defaultIfBlank(normalizeStatusValue(left), "clarified"));
        int rightIndex = order.indexOf(defaultIfBlank(normalizeStatusValue(right), "clarified"));
        if (leftIndex == -1) {
            return right;
        }
        if (rightIndex == -1) {
            return left;
        }
        return leftIndex <= rightIndex ? left : right;
    }

    private void collectPendingTexts(JsonNode node, Set<String> collector) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText("").trim();
            if (StringUtils.hasText(text) && containsAny(text, "待排", "待查", "复查", "完善", "进一步")) {
                collector.add(text);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectPendingTexts(item, collector);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectPendingTexts(entry.getValue(), collector));
        }
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

    private String toProblemKey(String problem) {
        if (!StringUtils.hasText(problem)) {
            return "";
        }
        if (containsAny(problem, "心衰", "心力衰竭")) {
            return "heart_failure";
        }
        if (containsAny(problem, "房颤", "心房颤动")) {
            return "atrial_fibrillation";
        }
        if (containsAny(problem, "感染", "肺炎")) {
            return "pulmonary_infection";
        }
        if (containsAny(problem, "贫血")) {
            return "anemia";
        }
        if (containsAny(problem, "血小板")) {
            return "thrombocytopenia";
        }
        if (containsAny(problem, "乳酸")) {
            return "hyperlactatemia";
        }
        if (containsAny(problem, "冠脉", "冠心病", "ACS")) {
            return "acs";
        }
        return problem.replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5]+", "_").toLowerCase();
    }

    private String inferProblemType(String problem) {
        if (containsAny(problem, "风险")) {
            return "risk_state";
        }
        if (containsAny(problem, "高血压", "COPD", "前列腺增生", "慢性")) {
            return "chronic";
        }
        if (containsAny(problem, "贫血", "血小板", "高乳酸", "胸腔积液")) {
            return "complication";
        }
        return "disease";
    }

    private String mapFusionCertainty(String certainty) {
        return defaultIfBlank(normalizeCertaintyValue(certainty), "confirmed");
    }

    private String mapFusionStatus(String status) {
        return defaultIfBlank(normalizeStatusValue(status), "active");
    }

    private String normalizeCertaintyValue(String certainty) {
        String normalized = defaultIfBlank(certainty, "").trim().toLowerCase();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return switch (normalized) {
            case "confirmed", "suspected", "possible", "workup_needed", "risk_only" -> normalized;
            case "differential" -> "workup_needed";
            default -> "";
        };
    }

    private String normalizeStatusValue(String status) {
        String normalized = defaultIfBlank(status, "").trim().toLowerCase();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return switch (normalized) {
            case "newly_identified" -> "active";
            case "worsened" -> "worsening";
            case "improved" -> "improving";
            case "active", "acute_exacerbation", "worsening", "improving", "chronic", "stable", "clarified", "unclear" -> normalized;
            default -> "";
        };
    }

    private String inferEtiologyTargetProblemKey(String diagnosis) {
        if (containsAny(diagnosis, "冠心病", "瓣膜")) {
            return "heart_failure";
        }
        return "";
    }

    private List<Map<String, Object>> buildEvidenceBucket(Map<String, Object> standardizedDayFacts, EvidenceBucket bucket) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : readStructuredNotes(standardizedDayFacts)) {
            String noteType = valueAsString(note.get("note_type"));
            String noteRef = buildNoteRef(noteType, valueAsString(note.get("timestamp")));
            JsonNode node = toStructuredNode(note.get("structured"));
            List<String> values = extractEvidenceByBucket(node, bucket);
            for (String value : values) {
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fact", value);
                item.put("related_problem_keys", inferProblemKeysFromText(value));
                item.put("source_types", List.of(noteType));
                item.put("source_note_refs", List.of(noteRef));
                result.add(item);
            }
        }
        return deduplicateMapList(result, "fact", 16);
    }

    private List<String> extractEvidenceByBucket(JsonNode node, EvidenceBucket bucket) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        switch (bucket) {
            case SYMPTOMS_SIGNS -> {
                collectFieldText(node, values, "new_findings", "worsening_points", "improving_points");
                JsonNode keyFindings = node.path("key_findings");
                if (keyFindings.isObject()) {
                    flattenText(keyFindings.path("vitals"), values);
                    flattenText(keyFindings.path("exam"), values);
                }
            }
            case LABS -> {
                collectFieldText(node, values, "new_findings", "key_exam_changes");
                JsonNode keyFindings = node.path("key_findings");
                if (keyFindings.isObject()) {
                    flattenText(keyFindings.path("labs"), values);
                }
                values.removeIf(value -> !containsAny(value, "Hb", "血", "CRP", "D-二聚体", "乳酸", "尿素", "肌钙蛋白", "NT-proBNP", "GGT", "葡萄糖", "甲功"));
            }
            case IMAGING -> {
                JsonNode keyFindings = node.path("key_findings");
                if (keyFindings.isObject()) {
                    flattenText(keyFindings.path("imaging"), values);
                }
                values.removeIf(value -> !containsAny(value, "CT", "彩超", "胸片", "积液", "炎症", "肺气肿", "结节"));
            }
            case OTHER_TESTS -> {
                JsonNode keyFindings = node.path("key_findings");
                if (keyFindings.isObject()) {
                    flattenText(keyFindings.path("imaging"), values);
                }
                collectFieldText(node, values, "key_exam_changes");
                values.removeIf(value -> !containsAny(value, "心电图", "房颤", "ST", "右束支", "血气", "pO2", "EF"));
            }
        }
        return new ArrayList<>(values).subList(0, Math.min(values.size(), 8));
    }

    private List<Map<String, Object>> buildDetailedActions(Map<String, Object> standardizedDayFacts, ActionBucket bucket) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : readStructuredNotes(standardizedDayFacts)) {
            String noteType = valueAsString(note.get("note_type"));
            String noteRef = buildNoteRef(noteType, valueAsString(note.get("timestamp")));
            JsonNode node = toStructuredNode(note.get("structured"));
            List<String> actions = extractActionsByBucket(node, bucket);
            for (String action : actions) {
                if (!StringUtils.hasText(action)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("action", action);
                item.put("related_problem_keys", inferProblemKeysFromText(action));
                item.put("source_types", List.of(noteType));
                item.put("source_note_refs", List.of(noteRef));
                result.add(item);
            }
        }
        return deduplicateMapList(result, "action", 16);
    }

    private List<String> extractActionsByBucket(JsonNode node, ActionBucket bucket) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (node.path("initial_plan").isObject()) {
            JsonNode plan = node.path("initial_plan");
            switch (bucket) {
                case TREATMENT -> flattenText(plan.path("treatment"), actions);
                case DIAGNOSTIC -> flattenText(plan.path("tests"), actions);
                case MONITORING -> flattenText(plan.path("monitoring"), actions);
                case PROCESS -> {
                    flattenText(plan.path("education"), actions);
                    flattenText(plan.path("consults"), actions);
                    flattenText(plan.path("procedures"), actions);
                }
            }
        }
        switch (bucket) {
            case TREATMENT -> collectFieldText(node, actions, "treatment_adjustments", "urgent_actions", "major_actions", "immediate_postop_orders");
            case DIAGNOSTIC -> collectFieldText(node, actions, "next_focus_24h");
            case MONITORING -> collectFieldText(node, actions, "next_focus_24h");
            case PROCESS -> collectFieldText(node, actions, "treatment_adjustments", "next_focus_24h");
        }
        actions.removeIf(action -> switch (bucket) {
            case TREATMENT -> isDiagnosticPlan(action) || containsAny(action, "监护", "监测", "尿量", "同意书", "签署", "复查", "追踪");
            case DIAGNOSTIC -> !isDiagnosticLike(action);
            case MONITORING -> !containsAny(action, "监护", "监测", "尿量", "观察", "动态");
            case PROCESS -> !containsAny(action, "同意书", "签署", "饮食", "卧床", "休息", "宣教", "流程");
        });
        return new ArrayList<>(actions).subList(0, Math.min(actions.size(), 8));
    }

    private List<Map<String, Object>> buildPendingItems(Map<String, Object> standardizedDayFacts, PendingBucket bucket) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : readStructuredNotes(standardizedDayFacts)) {
            String noteType = valueAsString(note.get("note_type"));
            String noteRef = buildNoteRef(noteType, valueAsString(note.get("timestamp")));
            JsonNode node = toStructuredNode(note.get("structured"));
            List<String> items = extractPendingItems(node, bucket);
            for (String itemText : items) {
                if (!StringUtils.hasText(itemText)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("item", itemText);
                item.put("related_problem_keys", inferProblemKeysFromText(itemText));
                item.put("source_types", List.of(noteType));
                item.put("source_note_refs", List.of(noteRef));
                result.add(item);
            }
        }
        return deduplicateMapList(result, "item", 16);
    }

    private List<String> extractPendingItems(JsonNode node, PendingBucket bucket) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        collectFieldText(node, items, "next_focus_24h");
        JsonNode differential = node.path("differential_diagnosis");
        if (differential.isArray() && bucket == PendingBucket.DIAGNOSTIC) {
            for (JsonNode item : differential) {
                String diagnosis = item.path("diagnosis").asText("");
                String reason = item.path("reason").asText("");
                if (StringUtils.hasText(diagnosis)) {
                    items.add(diagnosis + (StringUtils.hasText(reason) ? "：" + reason : ""));
                }
            }
        }
        items.removeIf(item -> switch (bucket) {
            case DIAGNOSTIC -> !(containsAny(item, "待排", "待查", "明确", "鉴别", "完善", "原因"));
            case RECHECK -> !(containsAny(item, "复查", "动态", "追踪", "等待"));
            case PROCESS -> !(containsAny(item, "同意书", "流程", "签署"));
        });
        return new ArrayList<>(items).subList(0, Math.min(items.size(), 8));
    }

    private boolean isDiagnosticLike(String action) {
        return containsAny(action, "查", "复查", "完善", "动态复查", "追踪", "等待", "结果");
    }

    private List<String> inferProblemKeysFromText(String text) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (containsAny(text, "心衰", "心力衰竭", "NT-proBNP", "脑利钠肽", "胸腔积液", "水肿")) {
            keys.add("heart_failure");
        }
        if (containsAny(text, "房颤", "心房颤动", "心率绝对不齐", "脉搏短绌", "利伐沙班")) {
            keys.add("atrial_fibrillation");
        }
        if (containsAny(text, "感染", "肺炎", "咳嗽", "咳痰", "CRP", "白细胞", "头孢")) {
            keys.add("pulmonary_infection");
        }
        if (containsAny(text, "贫血", "Hb", "血红蛋白", "Hct", "贫血貌")) {
            keys.add("anemia");
        }
        if (containsAny(text, "血小板", "Plt")) {
            keys.add("thrombocytopenia");
        }
        if (containsAny(text, "乳酸", "Lac")) {
            keys.add("hyperlactatemia");
        }
        if (containsAny(text, "肌钙蛋白", "ACS", "冠脉", "ST改变")) {
            keys.add("acs");
        }
        return new ArrayList<>(keys);
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

    private enum EvidenceBucket {
        SYMPTOMS_SIGNS, LABS, IMAGING, OTHER_TESTS
    }

    private enum ActionBucket {
        TREATMENT, DIAGNOSTIC, MONITORING, PROCESS
    }

    private enum PendingBucket {
        DIAGNOSTIC, RECHECK, PROCESS
    }

    private ArrayNode ensureArrayNode(JsonNode node) {
        if (node != null && node.isArray()) {
            return (ArrayNode) node;
        }
        return objectMapper.createArrayNode();
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

    private List<String> extractProblemTexts(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectFieldText(node, values, "change_summary", "consult_reason", "consult_core_judgment",
                "new_problem_list", "surgery_goal_and_indication", "day_summary");
        JsonNode coreProblems = node.path("core_problems");
        if (coreProblems.isArray()) {
            for (JsonNode item : coreProblems) {
                String problem = item.path("problem").asText();
                if (StringUtils.hasText(problem)) {
                    values.add(problem.trim());
                }
            }
        }
        return new ArrayList<>(values);
    }

    private List<String> extractFindingTexts(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectFieldText(node, values, "key_exam_changes", "key_supporting_evidence", "intraoperative_key_findings",
                "critical_exam_or_pathology", "key_evidence");
        JsonNode keyFindings = node.path("key_findings");
        if (keyFindings.isObject()) {
            keyFindings.fields().forEachRemaining(entry -> flattenText(entry.getValue(), values));
        }
        return new ArrayList<>(values);
    }

    private List<String> extractPlanTexts(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectFieldText(node, values, "treatment_adjustments", "urgent_actions", "short_term_plan_24h",
                "immediate_postop_orders", "next_focus_24h", "major_actions");
        JsonNode initialPlan = node.path("initial_plan");
        if (initialPlan.isObject()) {
            initialPlan.fields().forEachRemaining(entry -> flattenText(entry.getValue(), values));
        }
        return new ArrayList<>(values);
    }

    private List<String> extractRiskTexts(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectFieldText(node, values, "risk_alerts", "postop_risk_alerts", "risk_flags");
        JsonNode coreProblems = node.path("core_problems");
        if (coreProblems.isArray()) {
            for (JsonNode item : coreProblems) {
                flattenText(item.path("risk"), values);
            }
        }
        return new ArrayList<>(values);
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
                                                                       JsonNode dailyFusionNode,
                                                                       Set<String> sourceRefs) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> timelineEntry = new LinkedHashMap<>();
        timelineEntry.put("time", rawData.getDataDate() == null ? "" : rawData.getDataDate().toString());
        timelineEntry.put("record_type", "daily_fusion");
        timelineEntry.put("day_summary", dailyFusionNode.path("day_summary").asText(""));
        timelineEntry.put("daily_fusion", dailyFusionNode);
        timelineEntry.put("source_note_refs", new ArrayList<>(sourceRefs));
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

    private JsonNode applyClinicalBoundaryRules(JsonNode fusionNode,
                                                JsonNode rawRoot,
                                                JsonNode fusionReadyFacts,
                                                List<Map<String, Object>> noteStructuredList) {
        if (fusionNode == null || !fusionNode.isObject()) {
            return fusionNode;
        }
        ObjectNode root = (ObjectNode) fusionNode.deepCopy();
        String rawText = rawRoot == null ? "" : rawRoot.toString();
        boolean hasConfirmedPulmonaryEmbolism = containsAny(rawText, "确诊肺栓塞", "肺栓塞确诊", "CTPA提示肺栓塞");

        JsonNode problemListNode = root.get("problem_list");
        if (problemListNode != null && problemListNode.isArray()) {
            ArrayNode normalizedProblems = objectMapper.createArrayNode();
            for (JsonNode node : problemListNode) {
                if (!node.isObject()) {
                    continue;
                }
                ObjectNode problem = (ObjectNode) node.deepCopy();
                normalizeProblemBoundary(problem, hasConfirmedPulmonaryEmbolism);
                normalizeProblemType(problem);
                normalizeStatus(problem, rawText);
                normalizeCertainty(problem);
                normalizePriority(problem);
                normalizeEvidence(problem);
                normalizeMajorActions(problem);
                normalizeRiskFlags(problem, hasConfirmedPulmonaryEmbolism);
                backfillProblemEvidence(problem, fusionReadyFacts, noteStructuredList);
                backfillProblemActions(problem, fusionReadyFacts);
                backfillProblemSourceRefs(problem, noteStructuredList);
                refineProblemCertainty(problem, rawText);
                if (!shouldKeepProblem(problem)) {
                    continue;
                }
                normalizedProblems.add(problem);
            }
            root.set("problem_list", normalizedProblems);
        }
        backfillTopLevelEvidence(root, fusionReadyFacts);
        backfillTopLevelActions(root, fusionReadyFacts);
        backfillTopLevelRisks(root);
        backfillTopLevelSourceRefs(root, noteStructuredList);
        return root;
    }

    private void normalizeProblemBoundary(ObjectNode problemNode, boolean hasConfirmedPulmonaryEmbolism) {
        String problem = problemNode.path("problem").asText("");
        if (!StringUtils.hasText(problem)) {
            return;
        }
        if (!hasConfirmedPulmonaryEmbolism && containsAny(problem, "疑似肺栓塞", "肺栓塞")) {
            String normalized = problem.replace("及疑似肺栓塞", "")
                    .replace("疑似肺栓塞", "")
                    .replace("肺栓塞", "血栓风险待排")
                    .trim();
            if (!StringUtils.hasText(normalized)) {
                normalized = "血栓风险待排";
            }
            problemNode.put("problem", normalized);
        }
    }

    private void normalizeStatus(ObjectNode problemNode, String rawText) {
        String currentStatus = normalizeStatusValue(problemNode.path("status").asText(""));
        String problem = problemNode.path("problem").asText("");
        String inferred = inferStatus(problem, rawText);
        if (!ALLOWED_STATUS.contains(currentStatus)) {
            problemNode.put("status", inferred);
            return;
        }
        if ("newly_identified".equals(currentStatus) && "chronic".equals(inferred)) {
            problemNode.put("status", "clarified");
            return;
        }
        if ("worsened".equals(currentStatus) && !"acute_exacerbation".equals(inferred)) {
            problemNode.put("status", inferred);
        }
    }

    private void normalizeProblemType(ObjectNode problemNode) {
        String currentType = defaultIfBlank(problemNode.path("problem_type").asText(""), "").trim().toLowerCase();
        if (ALLOWED_PROBLEM_TYPE.contains(currentType)) {
            problemNode.put("problem_type", currentType);
            return;
        }
        problemNode.put("problem_type", inferProblemType(problemNode.path("problem").asText("")));
    }

    private String inferStatus(String problem, String rawText) {
        String text = (problem + " " + rawText);
        if (containsAny(problem, "感染", "肺炎") && !containsAny(problem, "慢阻肺", "COPD", "急性加重")) {
            return "clarified";
        }
        if (containsAny(text, "急性加重", "再发加重", "加重")) {
            return "acute_exacerbation";
        }
        if (containsAny(text, "3+年", "8+年", "7+月", "慢性", "长期", "既往", "病史", "COPD", "高血压", "房颤", "心衰")) {
            return "chronic";
        }
        if (containsAny(text, "新发", "补充诊断", "首次发现")) {
            return "active";
        }
        return "clarified";
    }

    private void normalizeCertainty(ObjectNode problemNode) {
        String current = normalizeCertaintyValue(problemNode.path("certainty").asText(""));
        if (ALLOWED_CERTAINTY.contains(current)) {
            problemNode.put("certainty", current);
            return;
        }
        String problem = problemNode.path("problem").asText("");
        List<String> evidence = readTextArray(problemNode.get("key_evidence"));
        String merged = problem + " " + String.join(" ", evidence);
        String certainty;
        if (containsAny(merged, "疑似", "可能", "考虑")) {
            certainty = "suspected";
        } else if (containsAny(merged, "风险", "高危", "待排")) {
            certainty = "risk_only";
        } else if (containsAny(merged, "排查", "完善", "复查", "待查")) {
            certainty = "workup_needed";
        } else {
            certainty = "confirmed";
        }
        problemNode.put("certainty", certainty);
    }

    private void normalizePriority(ObjectNode problemNode) {
        String current = defaultIfBlank(problemNode.path("priority").asText(""), "").trim().toLowerCase();
        if (ALLOWED_PRIORITY.contains(current)) {
            problemNode.put("priority", current);
            return;
        }
        problemNode.put("priority", "");
    }

    private void normalizeEvidence(ObjectNode problemNode) {
        List<String> values = readTextArray(problemNode.get("key_evidence"));
        ArrayNode normalized = objectMapper.createArrayNode();
        for (String value : values) {
            String text = value.replace("确诊", "提示").trim();
            if (StringUtils.hasText(text)) {
                normalized.add(text);
            }
        }
        problemNode.set("key_evidence", normalized);
    }

    private void normalizeMajorActions(ObjectNode problemNode) {
        List<String> values = readTextArray(problemNode.get("major_actions"));
        ArrayNode normalized = objectMapper.createArrayNode();
        for (String value : values) {
            if (containsAny(value, "可能", "考虑", "解释", "评估", "降低", "改善", "警惕") || isDiagnosticPlan(value)) {
                continue;
            }
            String cleaned = value.replaceFirst("^(利尿|抗感染|抗凝|抗心衰|退热|吸氧|补液|会诊|营养心肌|止咳化痰)[:：]", "").trim();
            if (StringUtils.hasText(cleaned)) {
                normalized.add(cleaned);
            }
        }
        problemNode.set("major_actions", normalized);
    }

    private void normalizeRiskFlags(ObjectNode problemNode, boolean hasConfirmedPulmonaryEmbolism) {
        List<String> values = readTextArray(problemNode.get("risk_flags"));
        LinkedHashSet<String> normalizedValues = new LinkedHashSet<>();
        for (String value : values) {
            if (!hasConfirmedPulmonaryEmbolism && "肺栓塞".equals(value)) {
                normalizedValues.add("肺栓塞风险待排");
                continue;
            }
            if (containsAny(value, "脓毒症风险", "恶性心律失常风险", "急性呼吸衰竭风险", "组织缺氧风险", "肾功能受损风险")
                    && !containsAny(value, "原文明确", "已提示")) {
                continue;
            }
            normalizedValues.add(value);
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        normalizedValues.forEach(normalized::add);
        problemNode.set("risk_flags", normalized);
    }

    private void backfillProblemEvidence(ObjectNode problemNode,
                                         JsonNode fusionReadyFacts,
                                         List<Map<String, Object>> noteStructuredList) {
        if (problemNode.path("key_evidence").isArray() && problemNode.path("key_evidence").size() > 0) {
            return;
        }
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        String problem = problemNode.path("problem").asText("");
        addMatchingEvidence(evidence, flattenObjectiveEvidence(fusionReadyFacts), problem, 3);
        if (evidence.isEmpty()) {
            addMatchingStructuredFindings(evidence, noteStructuredList, problem, 3);
        }
        ArrayNode node = objectMapper.createArrayNode();
        evidence.forEach(node::add);
        problemNode.set("key_evidence", node);
    }

    private void backfillProblemActions(ObjectNode problemNode, JsonNode fusionReadyFacts) {
        ArrayNode current = ensureArrayNode(problemNode.get("major_actions"));
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode item : current) {
            String text = item.asText("").trim();
            if (StringUtils.hasText(text) && !isDiagnosticPlan(text)) {
                normalized.add(text);
            }
        }
        if (normalized.isEmpty()) {
            LinkedHashSet<String> actions = new LinkedHashSet<>();
            addMatchingTexts(actions, flattenActionCandidates(fusionReadyFacts),
                    problemNode.path("problem").asText(""), 3);
            actions.stream().filter(text -> !isDiagnosticPlan(text)).forEach(normalized::add);
        }
        problemNode.set("major_actions", normalized);
    }

    private void backfillProblemSourceRefs(ObjectNode problemNode, List<Map<String, Object>> noteStructuredList) {
        ArrayNode current = ensureArrayNode(problemNode.get("source_note_refs"));
        if (current.size() > 1) {
            return;
        }
        LinkedHashSet<String> refs = inferSourceRefsByProblem(problemNode.path("problem").asText(""), noteStructuredList);
        if (refs.isEmpty() && current.size() > 0) {
            return;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        refs.forEach(normalized::add);
        problemNode.set("source_note_refs", normalized);
    }

    private void backfillTopLevelEvidence(ObjectNode root, JsonNode fusionReadyFacts) {
        ArrayNode current = ensureArrayNode(root.get("key_evidence"));
        if (current.size() > 0) {
            return;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        JsonNode candidates = flattenObjectiveEvidence(fusionReadyFacts);
        if (candidates != null && candidates.isArray()) {
            for (JsonNode item : candidates) {
                String text = item.asText("").trim();
                if (StringUtils.hasText(text)) {
                    evidence.add(text);
                }
                if (evidence.size() >= 6) {
                    break;
                }
            }
        }
        evidence.forEach(normalized::add);
        root.set("key_evidence", normalized);
    }

    private void backfillTopLevelActions(ObjectNode root, JsonNode fusionReadyFacts) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        JsonNode problemList = root.get("problem_list");
        if (problemList != null && problemList.isArray()) {
            for (JsonNode item : problemList) {
                addAllStrings(actions, readTextArray(item.get("major_actions")));
            }
        }
        JsonNode candidates = flattenActionCandidates(fusionReadyFacts);
        if (candidates != null && candidates.isArray()) {
            List<String> prioritized = new ArrayList<>();
            List<String> secondary = new ArrayList<>();
            for (JsonNode item : candidates) {
                String text = item.asText("").trim();
                if (!StringUtils.hasText(text) || isDiagnosticPlan(text)) {
                    continue;
                }
                if (containsAny(text, "呋塞米", "螺内酯", "恩格列净", "利伐沙班", "头孢", "吸氧", "监护")) {
                    prioritized.add(text);
                } else {
                    secondary.add(text);
                }
            }
            prioritized.forEach(actions::add);
            secondary.forEach(actions::add);
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        int count = 0;
        for (String action : actions) {
            if (!StringUtils.hasText(action)) {
                continue;
            }
            normalized.add(action);
            count++;
            if (count >= 6) {
                break;
            }
        }
        root.set("major_actions", normalized);
    }

    private void backfillTopLevelRisks(ObjectNode root) {
        LinkedHashSet<String> risks = new LinkedHashSet<>();
        JsonNode problemList = root.get("problem_list");
        if (problemList != null && problemList.isArray()) {
            for (JsonNode item : problemList) {
                addAllStrings(risks, readTextArray(item.get("risk_flags")));
            }
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        int count = 0;
        for (String risk : risks) {
            if (!StringUtils.hasText(risk)) {
                continue;
            }
            normalized.add(risk);
            count++;
            if (count >= 6) {
                break;
            }
        }
        root.set("risk_flags", normalized);
    }

    private void backfillTopLevelSourceRefs(ObjectNode root, List<Map<String, Object>> noteStructuredList) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        JsonNode problemList = root.get("problem_list");
        if (problemList != null && problemList.isArray()) {
            for (JsonNode item : problemList) {
                addAllStrings(refs, readTextArray(item.get("source_note_refs")));
            }
        }
        if (refs.isEmpty()) {
            for (Map<String, Object> note : noteStructuredList) {
                String noteType = valueAsString(note.get("note_type")).trim();
                if (StringUtils.hasText(noteType)) {
                    refs.add(noteType);
                }
            }
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        refs.forEach(normalized::add);
        root.set("source_note_refs", normalized);
    }

    private void addMatchingTexts(Set<String> collector, JsonNode candidates, String problem, int limit) {
        if (candidates == null || !candidates.isArray()) {
            return;
        }
        List<String> keywords = inferProblemKeywords(problem);
        for (JsonNode item : candidates) {
            String text = item.asText("").trim();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (matchesProblem(text, keywords)) {
                collector.add(text);
            }
            if (collector.size() >= limit) {
                break;
            }
        }
    }

    private void addMatchingStructuredFindings(Set<String> collector,
                                               List<Map<String, Object>> noteStructuredList,
                                               String problem,
                                               int limit) {
        List<String> keywords = inferProblemKeywords(problem);
        for (Map<String, Object> note : noteStructuredList) {
            Object structuredNode = note.get("structured");
            JsonNode node = structuredNode instanceof JsonNode jsonNode
                    ? jsonNode
                    : objectMapper.valueToTree(structuredNode);
            for (String finding : extractFindingTexts(node)) {
                if (matchesProblem(finding, keywords)) {
                    collector.add(finding);
                }
                if (collector.size() >= limit) {
                    return;
                }
            }
        }
    }

    private LinkedHashSet<String> inferSourceRefsByProblem(String problem, List<Map<String, Object>> noteStructuredList) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        List<String> keywords = inferProblemKeywords(problem);
        for (Map<String, Object> note : noteStructuredList) {
            String noteType = valueAsString(note.get("note_type")).trim();
            Object structuredNode = note.get("structured");
            JsonNode node = structuredNode instanceof JsonNode jsonNode
                    ? jsonNode
                    : objectMapper.valueToTree(structuredNode);
            String merged = node.toString();
            if (matchesProblem(merged, keywords)) {
                refs.add(noteType);
            }
        }
        return refs;
    }

    private List<String> inferProblemKeywords(String problem) {
        List<String> keywords = new ArrayList<>();
        if (containsAny(problem, "心衰", "心力衰竭")) {
            keywords.addAll(List.of("心衰", "心力衰竭", "NT-proBNP", "脑利钠肽", "呋塞米", "螺内酯", "恩格列净", "胸腔积液"));
        }
        if (containsAny(problem, "房颤", "心房颤动")) {
            keywords.addAll(List.of("房颤", "心房颤动", "心率绝对不齐", "脉搏短绌", "利伐沙班", "CHA2DS2", "心电图"));
        }
        if (containsAny(problem, "感染", "肺炎")) {
            keywords.addAll(List.of("感染", "肺炎", "CRP", "C反应蛋白", "白细胞", "中性粒", "头孢", "炎症"));
        }
        if (containsAny(problem, "贫血")) {
            keywords.addAll(List.of("贫血", "血红蛋白", "Hct", "红细胞压积", "贫血貌"));
        }
        if (containsAny(problem, "血小板")) {
            keywords.addAll(List.of("血小板", "PLT"));
        }
        if (containsAny(problem, "高乳酸")) {
            keywords.addAll(List.of("乳酸", "Lac"));
        }
        if (keywords.isEmpty() && StringUtils.hasText(problem)) {
            keywords.add(problem);
        }
        return keywords;
    }

    private boolean matchesProblem(String text, List<String> keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void refineProblemCertainty(ObjectNode problemNode, String rawText) {
        String problem = problemNode.path("problem").asText("");
        String current = problemNode.path("certainty").asText("");
        List<String> evidence = readTextArray(problemNode.get("key_evidence"));
        String mergedEvidence = String.join(" ", evidence);
        if (containsAny(problem, "心衰", "心力衰竭")) {
            if (containsAny(rawText, "充血性心力衰竭", "心功能Ⅳ级", "NT-BNP", "NT-proBNP")
                    || containsAny(mergedEvidence, "NT-proBNP", "脑利钠肽", "胸腔积液", "下肢水肿")) {
                problemNode.put("certainty", "confirmed");
                return;
            }
        }
        if (containsAny(problem, "房颤", "心房颤动")) {
            if (containsAny(rawText, "心房颤动", "心率绝对不齐", "脉搏短绌") || containsAny(mergedEvidence, "心电图", "心房颤动")) {
                problemNode.put("certainty", "confirmed");
                return;
            }
        }
        if (containsAny(problem, "感染", "肺炎") && containsAny(problem, "待排")) {
            problemNode.put("certainty", "suspected");
            return;
        }
        if (!StringUtils.hasText(current)) {
            normalizeCertainty(problemNode);
        }
    }

    private boolean shouldKeepProblem(ObjectNode problemNode) {
        String certainty = problemNode.path("certainty").asText("");
        int evidenceCount = ensureArrayNode(problemNode.get("key_evidence")).size();
        int actionCount = ensureArrayNode(problemNode.get("major_actions")).size();
        int riskCount = ensureArrayNode(problemNode.get("risk_flags")).size();
        if (containsAny(certainty, "workup_needed", "risk_only") && evidenceCount == 0 && actionCount == 0 && riskCount == 0) {
            return false;
        }
        return StringUtils.hasText(problemNode.path("problem").asText(""));
    }

    private void addMatchingEvidence(Set<String> collector, JsonNode candidates, String problem, int limit) {
        if (candidates == null || !candidates.isArray()) {
            return;
        }
        ProblemProfile profile = buildProblemProfile(problem);
        for (JsonNode item : candidates) {
            String text = item.asText("").trim();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (matchesEvidence(text, profile)) {
                collector.add(text);
            }
            if (collector.size() >= limit) {
                break;
            }
        }
    }

    private boolean matchesEvidence(String text, ProblemProfile profile) {
        if (!StringUtils.hasText(text) || profile == null) {
            return false;
        }
        boolean include = profile.includeKeywords.isEmpty();
        for (String keyword : profile.includeKeywords) {
            if (text.contains(keyword)) {
                include = true;
                break;
            }
        }
        if (!include) {
            return false;
        }
        for (String keyword : profile.excludeKeywords) {
            if (text.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    private ProblemProfile buildProblemProfile(String problem) {
        ProblemProfile profile = new ProblemProfile();
        if (containsAny(problem, "心衰", "心力衰竭")) {
            profile.includeKeywords.addAll(List.of("NT-proBNP", "脑利钠肽", "下肢水肿", "胸腔积液", "气促", "心脏增大"));
            profile.excludeKeywords.addAll(List.of("CRP", "白细胞", "中性粒", "D-二聚体", "肺炎"));
            return profile;
        }
        if (containsAny(problem, "房颤", "心房颤动")) {
            profile.includeKeywords.addAll(List.of("心电图", "心房颤动", "心率绝对不齐", "脉搏短绌"));
            profile.excludeKeywords.addAll(List.of("CRP", "白细胞", "胸部CT"));
            return profile;
        }
        if (containsAny(problem, "感染", "肺炎", "肺部")) {
            profile.includeKeywords.addAll(List.of("胸部CT", "炎症", "CRP", "白细胞", "中性粒", "咳嗽", "咳痰"));
            profile.excludeKeywords.addAll(List.of("NT-proBNP", "脑利钠肽", "心房颤动", "D-二聚体"));
            return profile;
        }
        if (containsAny(problem, "冠脉", "ACS", "冠心病")) {
            profile.includeKeywords.addAll(List.of("胸痛", "肌钙蛋白", "TNT", "ST改变", "排查ACS"));
            profile.excludeKeywords.addAll(List.of("CRP", "白细胞", "NT-proBNP"));
            return profile;
        }
        if (containsAny(problem, "贫血")) {
            profile.includeKeywords.addAll(List.of("血红蛋白", "Hct", "红细胞压积", "贫血貌"));
            return profile;
        }
        return profile;
    }

    private static class ProblemProfile {
        private final List<String> includeKeywords = new ArrayList<>();
        private final List<String> excludeKeywords = new ArrayList<>();
    }

    private boolean isDiagnosticPlan(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return (text.startsWith("查") || text.startsWith("复查") || text.startsWith("完善") || text.startsWith("择期复查")
                || text.startsWith("动态复查") || text.startsWith("追踪"))
                && !containsAny(text, "监护", "监测", "吸氧", "输液", "iv", "po", "st");
    }

    private JsonNode flattenObjectiveEvidence(JsonNode fusionReadyFacts) {
        ArrayNode flattened = objectMapper.createArrayNode();
        if (fusionReadyFacts == null || !fusionReadyFacts.isObject()) {
            return flattened;
        }
        JsonNode objectiveLayer = fusionReadyFacts.get("objective_fact_layer");
        if (objectiveLayer == null || !objectiveLayer.isObject()) {
            return flattened;
        }
        JsonNode objective = objectiveLayer.get("objective_evidence");
        if (objective == null || !objective.isObject()) {
            return flattened;
        }
        for (String field : List.of("symptoms_signs", "labs", "imaging", "other_tests")) {
            JsonNode items = objective.get(field);
            if (items == null || !items.isArray()) {
                continue;
            }
            for (JsonNode item : items) {
                String fact = item.path("fact").asText("").trim();
                if (StringUtils.hasText(fact)) {
                    flattened.add(fact);
                }
            }
        }
        return flattened;
    }

    private JsonNode flattenActionCandidates(JsonNode fusionReadyFacts) {
        ArrayNode flattened = objectMapper.createArrayNode();
        if (fusionReadyFacts == null || !fusionReadyFacts.isObject()) {
            return flattened;
        }
        JsonNode objectiveLayer = fusionReadyFacts.get("objective_fact_layer");
        if (objectiveLayer == null || !objectiveLayer.isObject()) {
            return flattened;
        }
        for (String field : List.of("treatment_actions", "monitoring_actions", "process_actions")) {
            JsonNode items = objectiveLayer.get(field);
            if (items == null || !items.isArray()) {
                continue;
            }
            for (JsonNode item : items) {
                String action = item.path("action").asText("").trim();
                if (StringUtils.hasText(action)) {
                    flattened.add(action);
                }
            }
        }
        return flattened;
    }

    private List<String> readTextArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private String selectPromptByItemName(String itemname) {
        return switch (IllnessRecordType.resolve(itemname)) {
            case FIRST_COURSE, ADMISSION -> FormatAgentPrompt.FIRST_ILLNESS_COURSE_PROMPT_NEW;
            case CONSULTATION -> FormatAgentPrompt.CONSULTATION_ILLNESS_COURSE_PROMPT_NEW;
            case SURGERY -> FormatAgentPrompt.SURGERY_ILLNESS_COURSE_PROMPT_NEW;
            case DAILY -> FormatAgentPrompt.ILLNESS_COURSE_PROMPT_NEW;
        };
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

    private JsonNode toJsonNodeOrText(String text) {
        JsonNode node = parseJsonQuietly(text);
        if (node != null) {
            return node;
        }
        return objectMapper.valueToTree(text);
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

    public record DailyIllnessResult(String structDataJson, String updatedSummaryJson) {
    }
}
