package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.domain.dto.PatientTimelineViewData;
import com.zzhy.yg_ai.domain.dto.demo.InfectionPreReviewDemoSnapshotResult;
import com.zzhy.yg_ai.domain.dto.demo.InfectionPreReviewSourceRow;
import com.zzhy.yg_ai.domain.entity.InfectionPreReviewDemoEntity;
import com.zzhy.yg_ai.domain.enums.InfectionBodySite;
import com.zzhy.yg_ai.domain.enums.InfectionNosocomialLikelihood;
import com.zzhy.yg_ai.mapper.InfectionPreReviewDemoMapper;
import com.zzhy.yg_ai.service.PatientTimelineViewService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfectionPreReviewDemoSnapshotService {

    private static final int TIMELINE_PAGE_NO = 1;
    private static final int TIMELINE_PAGE_SIZE = 50;
    private static final String TIMELINE_TEMPLATE_PATH = "static/patient_summary_timeline_view.html";
    private static final DateTimeFormatter SECOND_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InfectionMonitorProperties infectionMonitorProperties;
    private final PatientTimelineViewService patientTimelineViewService;
    private final InfectionPreReviewDemoMapper infectionPreReviewDemoMapper;
    private final ObjectMapper objectMapper;

    private volatile String timelineStyle;

    public InfectionPreReviewDemoSnapshotResult generateSnapshots() {
        List<String> reqnos = resolveDebugReqnos();
        List<InfectionPreReviewDemoSnapshotResult.Item> items = new ArrayList<>();
        int success = 0;
        int failed = 0;
        for (String reqno : reqnos) {
            try {
                generateOne(reqno);
                success++;
                items.add(new InfectionPreReviewDemoSnapshotResult.Item(reqno, true, "OK"));
            } catch (Exception e) {
                failed++;
                log.warn("院感预审演示快照生成失败，reqno={}", reqno, e);
                items.add(new InfectionPreReviewDemoSnapshotResult.Item(reqno, false, e.getMessage()));
            }
        }
        return new InfectionPreReviewDemoSnapshotResult(reqnos.size(), success, failed, List.copyOf(items));
    }

    private void generateOne(String reqno) throws IOException {
        PatientTimelineViewData timelineData = patientTimelineViewService.buildTimelineViewData(
                reqno,
                TIMELINE_PAGE_NO,
                TIMELINE_PAGE_SIZE
        );
        String timelineHtml = buildTimelineHtml(timelineData);
        String preReviewJson = buildPreReviewJson(reqno);

        InfectionPreReviewDemoEntity entity = new InfectionPreReviewDemoEntity();
        entity.setReqno(reqno);
        entity.setTimelineHtml(timelineHtml);
        entity.setAiPreReviewJson(preReviewJson);
        infectionPreReviewDemoMapper.upsertDemoSnapshot(entity);
    }

    private List<String> resolveDebugReqnos() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> configuredReqnos = infectionMonitorProperties.getDebugReqnos();
        if (configuredReqnos == null) {
            return List.of();
        }
        for (String raw : configuredReqnos) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            for (String part : raw.split(",")) {
                String reqno = part.trim();
                if (StringUtils.hasText(reqno)) {
                    result.add(reqno);
                }
            }
        }
        return List.copyOf(result);
    }

    private String buildPreReviewJson(String reqno) throws IOException {
        InfectionPreReviewSourceRow row = infectionPreReviewDemoMapper.selectLatestPreReviewByReqno(reqno);
        if (row == null) {
            throw new IllegalStateException("未查询到院感预审快照");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("reqno", reqno);
        if (row.getLastJudgeTime() == null) {
            root.putNull("lastJudgeTime");
        } else {
            root.put("lastJudgeTime", row.getLastJudgeTime().format(SECOND_FORMATTER));
        }
        root.put("primarySiteCode", defaultIfBlank(row.getPrimarySite(), InfectionBodySite.UNKNOWN.code()));
        root.put("primarySite", bodySiteLabel(row.getPrimarySite()));
        root.put("nosocomialLikelihood", defaultIfBlank(row.getNosocomialLikelihood(), ""));
        root.put("nosocomialLikelihoodLabel", nosocomialLikelihoodLabel(row.getNosocomialLikelihood()));

        if (!StringUtils.hasText(row.getResultJson())) {
            root.set("resultJson", buildDisplayResultJson(objectMapper.createObjectNode()));
            return objectMapper.writeValueAsString(root);
        }
        try {
            JsonNode resultNode = objectMapper.readTree(row.getResultJson());
            if (!resultNode.isObject()) {
                root.set("resultJson", buildDisplayResultJson(objectMapper.createObjectNode()));
                return objectMapper.writeValueAsString(root);
            }
            root.set("resultJson", buildDisplayResultJson((ObjectNode) resultNode));
        } catch (Exception e) {
            root.set("resultJson", buildDisplayResultJson(objectMapper.createObjectNode()));
            log.warn("院感预审 result_json 解析失败，reqno={}", reqno, e);
        }
        return objectMapper.writeValueAsString(root);
    }

    private ObjectNode buildDisplayResultJson(ObjectNode source) {
        ObjectNode result = objectMapper.createObjectNode();
        copyOrNull(source, result, "warningLevel");
        copyArrayOrEmpty(source, result, "decisionReason");
        copyArrayOrEmpty(source, result, "missingEvidenceReminders");
        result.set("aiSuggestions", normalizeAiSuggestions(source.path("aiSuggestions")));
        return result;
    }

    private void copyOrNull(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            target.putNull(fieldName);
            return;
        }
        target.set(fieldName, value.deepCopy());
    }

    private void copyArrayOrEmpty(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.path(fieldName);
        target.set(fieldName, value.isArray() ? value.deepCopy() : objectMapper.createArrayNode());
    }

    private ArrayNode normalizeAiSuggestions(JsonNode suggestionsNode) {
        if (!suggestionsNode.isArray()) {
            return objectMapper.createArrayNode();
        }
        List<SuggestionNode> suggestions = new ArrayList<>();
        int index = 0;
        for (JsonNode item : suggestionsNode) {
            if (!item.isObject()) {
                index++;
                continue;
            }
            String priority = text(item.path("priority"));
            ObjectNode suggestion = objectMapper.createObjectNode();
            suggestion.put("priority", priority);
            suggestion.put("category", categoryLabel(text(item.path("category"))));
            suggestion.put("text", text(item.path("text")));
            suggestions.add(new SuggestionNode(suggestion, priorityOrder(priority), index++));
        }
        suggestions.sort(Comparator
                .comparingInt(SuggestionNode::priorityOrder)
                .thenComparingInt(SuggestionNode::originalIndex));
        ArrayNode sorted = objectMapper.createArrayNode();
        suggestions.forEach(item -> sorted.add(item.node()));
        return sorted;
    }

    private String bodySiteLabel(String code) {
        if (!StringUtils.hasText(code)) {
            return InfectionBodySite.UNKNOWN.description();
        }
        try {
            return InfectionBodySite.fromCode(code.trim()).description();
        } catch (IllegalArgumentException e) {
            return InfectionBodySite.UNKNOWN.description();
        }
    }

    private String nosocomialLikelihoodLabel(String code) {
        InfectionNosocomialLikelihood value = InfectionNosocomialLikelihood.fromCodeOrDefault(code, null);
        return value == null ? "未知" : value.description();
    }

    private int priorityOrder(String priority) {
        return switch (defaultIfBlank(priority, "")) {
            case "high" -> 0;
            case "medium" -> 1;
            case "low" -> 2;
            default -> 99;
        };
    }

    private String categoryLabel(String category) {
        return switch (defaultIfBlank(category, "")) {
            case "diagnosis" -> "诊断";
            case "test" -> "检查";
            case "treatment" -> "治疗";
            case "monitoring" -> "监测";
            case "review" -> "复核";
            default -> "复核";
        };
    }

    private String buildTimelineHtml(PatientTimelineViewData data) throws IOException {
        List<PatientTimelineViewData.TimelineItem> items = data == null || data.getItems() == null
                ? List.of()
                : data.getItems();
        String reqno = data == null ? "" : defaultIfBlank(data.getReqno(), "");
        StringBuilder html = new StringBuilder(65536);
        html.append("<!DOCTYPE html>\n")
                .append("<html lang=\"zh-CN\">\n")
                .append("<head>\n")
                .append("    <meta charset=\"UTF-8\">\n")
                .append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("    <title>患者病程总览时间轴</title>\n")
                .append("    <style>\n")
                .append(loadTimelineStyle())
                .append("\n        .detail-day[hidden] { display: none; }\n")
                .append("    </style>\n")
                .append("</head>\n")
                .append("<body>\n")
                .append("<div class=\"shell\">\n")
                .append("    <div class=\"layout\">\n")
                .append("        <section class=\"panel\">\n")
                .append("            <div class=\"panel-head\">\n")
                .append("                <div class=\"panel-title\">时间轴</div>\n")
                .append("                <div class=\"panel-desc\">")
                .append(escape(reqno))
                .append(" / ")
                .append(items.size())
                .append(" 天</div>\n")
                .append("            </div>\n")
                .append("            <div class=\"timeline-scroll\" id=\"timelineList\">\n");
        appendTimelineList(html, items);
        html.append("            </div>\n")
                .append("        </section>\n")
                .append("        <section class=\"panel\">\n")
                .append("            <div class=\"panel-head\">\n")
                .append("                <div class=\"panel-title\">单日详情</div>\n")
                .append("                <div class=\"panel-desc\" id=\"detailMeta\">")
                .append(items.isEmpty() ? "未选择" : escape(defaultIfBlank(items.get(0).getDate(), "-")))
                .append("</div>\n")
                .append("            </div>\n")
                .append("            <div class=\"detail-wrap\" id=\"detailPane\">\n");
        appendTimelineDetails(html, items);
        html.append("            </div>\n")
                .append("        </section>\n")
                .append("    </div>\n")
                .append("</div>\n")
                .append("<script>\n")
                .append("(function () {\n")
                .append("    document.querySelectorAll('.day-card').forEach(function (card) {\n")
                .append("        card.addEventListener('click', function () {\n")
                .append("            var index = card.getAttribute('data-index');\n")
                .append("            document.querySelectorAll('.day-card').forEach(function (item) { item.classList.remove('active'); });\n")
                .append("            card.classList.add('active');\n")
                .append("            document.querySelectorAll('.detail-day').forEach(function (item) { item.hidden = item.getAttribute('data-index') !== index; });\n")
                .append("            var date = card.getAttribute('data-date') || '-';\n")
                .append("            var meta = document.getElementById('detailMeta');\n")
                .append("            if (meta) meta.textContent = date;\n")
                .append("        });\n")
                .append("    });\n")
                .append("})();\n")
                .append("</script>\n")
                .append("</body>\n")
                .append("</html>\n");
        return html.toString();
    }

    private void appendTimelineList(StringBuilder html, List<PatientTimelineViewData.TimelineItem> items) {
        if (items.isEmpty()) {
            html.append("                <div class=\"empty\">未查询到可展示的 timeline 数据</div>\n");
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            PatientTimelineViewData.TimelineItem item = items.get(i);
            String severityClass = severityClass(item.getSeverity());
            html.append("                <article class=\"day-card")
                    .append(i == 0 ? " active" : "")
                    .append(item.isKeyDay() ? " key-day key-day-" + severityClass : "")
                    .append(hasBadge(item, "手术日") ? " surgery-day" : "")
                    .append("\" data-index=\"")
                    .append(i)
                    .append("\" data-date=\"")
                    .append(escapeAttr(defaultIfBlank(item.getDate(), "-")))
                    .append("\">\n")
                    .append("                    <span class=\"risk-dot risk-dot-")
                    .append(severityClass)
                    .append("\"></span>\n")
                    .append("                    <div class=\"day-date\">")
                    .append(escape(defaultIfBlank(item.getDate(), "-")))
                    .append("</div>\n")
                    .append("                    <div class=\"day-title\">")
                    .append(escape(defaultIfBlank(item.getTitle(), "-")))
                    .append("</div>\n")
                    .append("                    <div class=\"day-summary\">")
                    .append(escape(defaultIfBlank(item.getSummary(), "无摘要")))
                    .append("</div>\n")
                    .append("                    <div class=\"pills\">");
            for (String badge : safeList(item.getBadges()).stream().limit(5).toList()) {
                html.append("<span class=\"pill ")
                        .append(pickPillClass(badge))
                        .append("\">")
                        .append(escape(badge))
                        .append("</span>");
            }
            html.append("</div>\n")
                    .append("                </article>\n");
        }
    }

    private void appendTimelineDetails(StringBuilder html, List<PatientTimelineViewData.TimelineItem> items) {
        if (items.isEmpty()) {
            html.append("                <div class=\"empty\">点击左侧任意一天查看详情</div>\n");
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            PatientTimelineViewData.TimelineItem item = items.get(i);
            html.append("                <div class=\"detail-day\" data-index=\"")
                    .append(i)
                    .append("\"")
                    .append(i == 0 ? "" : " hidden")
                    .append(">\n");
            appendSummaryCard(html, item);
            appendProblemSection(html, "主问题", item.getPrimaryProblems(), "暂无主问题");
            appendProblemSection(html, "次问题 / 风险待排", item.getSecondaryProblems(), "暂无次问题");
            appendRiskSection(html, item.getRiskItems());
            appendStringListSection(html, "关键处理", item.getActions(), "暂无关键处理");
            appendStringListSection(html, "24小时关注", item.getNextFocus(), "暂无关注项");
            appendEvidenceSection(html, item);
            html.append("                </div>\n");
        }
    }

    private void appendSummaryCard(StringBuilder html, PatientTimelineViewData.TimelineItem item) {
        html.append("                    <section class=\"summary-card\">\n")
                .append("                        <div class=\"summary-head\"><span class=\"summary-date\">")
                .append(escape(defaultIfBlank(item.getDate(), "-")))
                .append("</span><span class=\"pill\">")
                .append(escape(defaultIfBlank(item.getSeverityLabel(), severityLabel(item.getSeverity()))))
                .append("</span>");
        for (String badge : safeList(item.getBadges())) {
            html.append("<span class=\"pill ")
                    .append(pickPillClass(badge))
                    .append("\">")
                    .append(escape(badge))
                    .append("</span>");
        }
        html.append("</div>\n")
                .append("                        <div class=\"summary-text\">")
                .append(escape(defaultIfBlank(item.getSummary(), "无摘要")))
                .append("</div>\n")
                .append("                    </section>\n");
    }

    private void appendProblemSection(StringBuilder html,
                                      String title,
                                      List<PatientTimelineViewData.ProblemItem> problems,
                                      String emptyText) {
        html.append("                    <section class=\"section\">\n")
                .append("                        <div class=\"section-toggle\"><span class=\"section-title\">")
                .append(escape(title))
                .append("</span></div>\n")
                .append("                        <div class=\"section-body\">\n");
        if (problems == null || problems.isEmpty()) {
            html.append("                            <div class=\"empty\">").append(escape(emptyText)).append("</div>\n");
        } else {
            html.append("                            <div class=\"problem-grid\">\n");
            for (PatientTimelineViewData.ProblemItem problem : problems) {
                appendProblemCard(html, problem);
            }
            html.append("                            </div>\n");
        }
        html.append("                        </div>\n")
                .append("                    </section>\n");
    }

    private void appendProblemCard(StringBuilder html, PatientTimelineViewData.ProblemItem problem) {
        html.append("                                <article class=\"problem-card\">\n")
                .append("                                    <div class=\"problem-name\">")
                .append(escape(defaultIfBlank(problem.getName(), "-")))
                .append("</div>\n")
                .append("                                    <div class=\"problem-meta\"><span class=\"pill\">")
                .append(escape(defaultIfBlank(problem.getStatusLabel(), defaultIfBlank(problem.getStatus(), "未知状态"))))
                .append("</span><span class=\"pill\">")
                .append(escape(defaultIfBlank(problem.getCertaintyLabel(), defaultIfBlank(problem.getCertainty(), "未知判断"))))
                .append("</span></div>\n")
                .append("                                    <div class=\"panel-desc\">关键证据</div>")
                .append(listHtml(problem.getEvidence(), "problem-list"))
                .append("                                    <div class=\"panel-desc\">关键处理</div>")
                .append(listHtml(problem.getActions(), "problem-list"));
        if (problem.getRisks() != null && !problem.getRisks().isEmpty()) {
            html.append("                                    <div class=\"panel-desc\">关联风险</div>")
                    .append(listHtml(problem.getRisks(), "problem-list"));
        }
        html.append("                                </article>\n");
    }

    private void appendRiskSection(StringBuilder html, List<PatientTimelineViewData.RiskItem> risks) {
        html.append("                    <section class=\"section\">\n")
                .append("                        <div class=\"section-toggle\"><span class=\"section-title\">风险待排</span></div>\n")
                .append("                        <div class=\"section-body\">\n");
        if (risks == null || risks.isEmpty()) {
            html.append("                            <div class=\"empty\">暂无风险待排</div>\n");
        } else {
            html.append("                            <div class=\"problem-grid\">\n");
            for (PatientTimelineViewData.RiskItem risk : risks) {
                html.append("                                <article class=\"problem-card\">\n")
                        .append("                                    <div class=\"problem-name\">")
                        .append(escape(defaultIfBlank(risk.getName(), "-")))
                        .append("</div>\n")
                        .append("                                    <div class=\"panel-desc\">依据</div>\n")
                        .append("                                    <div style=\"font-size:12px;color:#4d667e;line-height:1.8;margin-top:3px;\">")
                        .append(escape(defaultIfBlank(risk.getBasis(), "无")))
                        .append("</div>\n")
                        .append("                                </article>\n");
            }
            html.append("                            </div>\n");
        }
        html.append("                        </div>\n")
                .append("                    </section>\n");
    }

    private void appendStringListSection(StringBuilder html, String title, List<String> values, String emptyText) {
        html.append("                    <section class=\"section\">\n")
                .append("                        <div class=\"section-toggle\"><span class=\"section-title\">")
                .append(escape(title))
                .append("</span></div>\n")
                .append("                        <div class=\"section-body\">")
                .append(values == null || values.isEmpty() ? "<div class=\"empty\">" + escape(emptyText) + "</div>" : listHtml(values, "mini-list"))
                .append("</div>\n")
                .append("                    </section>\n");
    }

    private void appendEvidenceSection(StringBuilder html, PatientTimelineViewData.TimelineItem item) {
        html.append("                    <section class=\"section\">\n")
                .append("                        <div class=\"section-toggle\"><span class=\"section-title\">证据与来源</span></div>\n")
                .append("                        <div class=\"section-body\">\n")
                .append("                            <div class=\"panel-desc\" style=\"margin-bottom:4px;\">关键证据</div>\n")
                .append(listHtml(item.getEvidenceHighlights(), "mini-list"))
                .append("                            <div class=\"panel-desc\" style=\"margin:8px 0 4px;\">来源文书</div>\n")
                .append("                            <div class=\"source-list\">");
        if (item.getSourceNotes() == null || item.getSourceNotes().isEmpty()) {
            html.append("<span class=\"panel-desc\">无来源文书</span>");
        } else {
            for (PatientTimelineViewData.SourceNote note : item.getSourceNotes()) {
                String label = StringUtils.hasText(note.getTime())
                        ? note.getName() + " @ " + note.getTime()
                        : note.getName();
                html.append("<span class=\"source-link\">")
                        .append(escape(defaultIfBlank(label, "来源")))
                        .append("</span>");
            }
        }
        html.append("</div>\n")
                .append("                        </div>\n")
                .append("                    </section>\n");
    }

    private String listHtml(List<String> values, String cls) {
        if (values == null || values.isEmpty()) {
            return "<div class=\"empty\">暂无数据</div>";
        }
        StringBuilder html = new StringBuilder("<ul class=\"").append(escapeAttr(cls)).append("\">");
        for (String value : values) {
            html.append("<li>").append(escape(value)).append("</li>");
        }
        return html.append("</ul>").toString();
    }

    private String loadTimelineStyle() throws IOException {
        String style = timelineStyle;
        if (style != null) {
            return style;
        }
        String template = new ClassPathResource(TIMELINE_TEMPLATE_PATH).getContentAsString(StandardCharsets.UTF_8);
        int styleStart = template.indexOf("<style>");
        int styleEnd = template.indexOf("</style>");
        if (styleStart < 0 || styleEnd <= styleStart) {
            throw new IllegalStateException("时间线页面样式读取失败");
        }
        style = template.substring(styleStart + "<style>".length(), styleEnd);
        timelineStyle = style;
        return style;
    }

    private String severityClass(String severity) {
        return switch (defaultIfBlank(severity, "").toLowerCase()) {
            case "high", "critical", "severe" -> "high";
            case "medium", "moderate" -> "medium";
            case "low", "mild" -> "low";
            default -> "normal";
        };
    }

    private String severityLabel(String severity) {
        return switch (defaultIfBlank(severity, "")) {
            case "high" -> "高";
            case "medium" -> "中";
            case "low" -> "低";
            case "mild" -> "轻度";
            case "moderate" -> "中度";
            case "severe" -> "重度";
            case "critical" -> "危重";
            default -> "未分级";
        };
    }

    private String pickPillClass(String tag) {
        if ("高风险".equals(tag) || "发热".equals(tag)) {
            return "danger";
        }
        if ("拟出院".equals(tag)) {
            return "success";
        }
        if ("手术日".equals(tag) || "会诊".equals(tag) || "术后第1天".equals(tag) || "术后".equals(tag)) {
            return "info";
        }
        return "";
    }

    private boolean hasBadge(PatientTimelineViewData.TimelineItem item, String badge) {
        return item.getBadges() != null && item.getBadges().contains(badge);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(defaultIfBlank(value, ""));
    }

    private String escapeAttr(String value) {
        return HtmlUtils.htmlEscape(defaultIfBlank(value, ""));
    }

    private record SuggestionNode(
            ObjectNode node,
            int priorityOrder,
            int originalIndex
    ) {
    }
}
