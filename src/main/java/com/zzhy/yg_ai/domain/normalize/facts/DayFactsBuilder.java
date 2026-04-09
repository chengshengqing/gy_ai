package com.zzhy.yg_ai.domain.normalize.facts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DayFactsBuilder {

    public Map<String, Object> buildStandardizedDayFacts(JsonNode rawRoot, List<Map<String, Object>> noteStructuredList) {
        Map<String, Object> dayContext = new LinkedHashMap<>();
        dayContext.put("data_presence", buildDataPresence(rawRoot, noteStructuredList));
        dayContext.put("structured", noteStructuredList);
        dayContext.put("diagnosis_facts", buildDiagnosisFacts(rawRoot));
        dayContext.put("vitals_summary", summarizeVitals(rawRoot.path("vital_signs")));
        dayContext.put("lab_summary", summarizeLabs(rawRoot.path("lab_results")));
        dayContext.put("imaging_summary", summarizeImaging(rawRoot.path("imaging")));
        dayContext.put("orders_summary", summarizeDoctorOrders(rawRoot.path("doctor_orders")));
        dayContext.put("objective_events", buildObjectiveEvents(rawRoot));
        return dayContext;
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

    private List<Map<String, Object>> buildDiagnosisFacts(JsonNode rawRoot) {
        List<Map<String, Object>> diagnosisFacts = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
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

    private Map<String, Object> summarizeVitals(JsonNode vitalNode) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (vitalNode == null || !vitalNode.isArray() || vitalNode.isEmpty()) {
            summary.put("has_data", false);
            return summary;
        }

        double maxTemp = Double.NEGATIVE_INFINITY;
        double minTemp = Double.POSITIVE_INFINITY;
        double maxPulse = Double.NEGATIVE_INFINITY;
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
            double pulse = parseDouble(item.path("pulse").asText(""));
            if (!Double.isNaN(pulse)) {
                maxPulse = Math.max(maxPulse, pulse);
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

    private void collectArrayText(JsonNode node, Collection<String> collector) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            String text = item == null ? "" : item.asText("").trim();
            if (StringUtils.hasText(text)) {
                collector.add(text);
            }
        }
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
