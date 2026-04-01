package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.config.TimelineViewRuleProperties;
import com.zzhy.yg_ai.domain.dto.PatientTimelineViewData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.mapper.PatientRawDataMapper;
import com.zzhy.yg_ai.service.PatientTimelineViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 将 patient_raw_data.event_json 转换为前端可直接渲染的 timelineViewData。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientTimelineViewServiceImpl implements PatientTimelineViewService {

    private final PatientRawDataMapper patientRawDataMapper;
    private final ObjectMapper objectMapper;
    private final TimelineViewRuleProperties ruleProperties;

    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();
    private volatile Map<String, String> statusLabelMap;
    private volatile Map<String, String> certaintyLabelMap;
    private volatile Map<String, String> severityLabelMap;
    private volatile Map<String, String> problemTypeLabelMap;

    @Override
    public PatientTimelineViewData buildTimelineViewData(String reqno, int pageNo, int pageSize) {
        PatientTimelineViewData result = new PatientTimelineViewData();
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, Math.min(pageSize, 50));
        result.setReqno(StringUtils.hasText(reqno) ? reqno.trim() : "");
        result.setPageNo(safePageNo);
        result.setPageSize(safePageSize);
        if (!StringUtils.hasText(reqno)) {
            return result;
        }
        QueryWrapper<PatientRawDataEntity> countWrapper = new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno.trim())
                .isNotNull("event_json");
        long total = patientRawDataMapper.selectCount(countWrapper);
        result.setTotal(total);
        result.setHasMore((long) safePageNo * safePageSize < total);
        if (total <= 0) {
            return result;
        }

        int offset = (safePageNo - 1) * safePageSize;
        QueryWrapper<PatientRawDataEntity> pageWrapper = new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno.trim())
                .isNotNull("event_json")
                .orderByDesc("data_date", "id")
                .last("OFFSET " + offset + " ROWS FETCH NEXT " + safePageSize + " ROWS ONLY");
        List<PatientRawDataEntity> rows = patientRawDataMapper.selectList(pageWrapper);
        List<TimelineBuildContext> contexts = new ArrayList<>();
        int pageOrder = 0;
        for (PatientRawDataEntity row : rows) {
            JsonNode timelineNode = readJson(row == null ? null : row.getEventJson());
            TimelineBuildContext context = convertTimelineItem(timelineNode);
            if (context != null) {
                context.pageOrder = pageOrder++;
                contexts.add(context);
            }
        }

        contexts.sort(Comparator.comparing(c -> c.date, Comparator.nullsLast(LocalDate::compareTo)));
        if (!contexts.isEmpty()) {
            applyAutoGenerationRules(contexts);
        }
        contexts.sort(Comparator.comparingInt(c -> c.pageOrder));

        List<PatientTimelineViewData.TimelineItem> items = new ArrayList<>();
        contexts.forEach(c -> items.add(c.item));
        result.setItems(items);
        return result;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("eventJson 解析失败", e);
            return null;
        }
    }

    private TimelineBuildContext convertTimelineItem(JsonNode timelineNode) {
        if (timelineNode == null || !timelineNode.isObject()) {
            return null;
        }

        JsonNode flat = pickFlatDailyFusion(timelineNode);
        String summary = firstNonBlank(textValue(timelineNode, "day_summary"), textValue(flat, "day_summary"));
        String date = normalizeDate(firstNonBlank(
                textValue(timelineNode, "time"),
                textValue(timelineNode, "date"),
                textValue(flat, "time"),
                textValue(flat, "date")
        ));
        String recordType = firstNonBlank(
                textValue(timelineNode, "record_type"),
                textValue(flat, "record_type"),
                "daily_fusion"
        );

        List<String> rawSourceRefs = dedupeStrings(textList(timelineNode.path("source_note_refs")));
        if (rawSourceRefs.isEmpty()) {
            rawSourceRefs = dedupeStrings(textList(flat.path("source_note_refs")));
        }
        List<String> dayRiskFlags = dedupeStrings(textList(flat.path("risk_flags")));
        List<String> dayActions = dedupeStrings(textList(flat.path("major_actions")));
        List<String> nextFocus = dedupeStrings(textList(flat.path("next_focus_24h")));

        List<PatientTimelineViewData.ProblemItem> primary = new ArrayList<>();
        List<PatientTimelineViewData.ProblemItem> secondary = new ArrayList<>();
        List<PatientTimelineViewData.RiskItem> riskItems = new ArrayList<>();
        List<String> problemRiskFlags = new ArrayList<>();
        List<String> allActions = new ArrayList<>(dayActions);
        List<String> allEvidence = new ArrayList<>(textList(flat.path("key_evidence")));
        List<String> allSourceRefs = new ArrayList<>(rawSourceRefs);
        String firstProblemName = "";
        boolean hasHighPriorityPrimary = false;
        boolean hasImportantUnconfirmed = false;

        JsonNode problemsNode = flat.path("problem_list");
        if (problemsNode.isArray()) {
            int idx = 0;
            for (JsonNode problemNode : problemsNode) {
                idx++;
                String name = textValue(problemNode, "problem");
                if (!StringUtils.hasText(name)) {
                    continue;
                }

                String priority = normalizeLower(textValue(problemNode, "priority"));
                String certainty = normalizeLower(textValue(problemNode, "certainty"));
                String status = normalizeLower(textValue(problemNode, "status"));
                String problemType = normalizeLower(textValue(problemNode, "problem_type"));
                List<String> evidence = dedupeStrings(textList(problemNode.path("key_evidence")));
                List<String> actions = dedupeStrings(textList(problemNode.path("major_actions")));
                List<String> risks = dedupeStrings(textList(problemNode.path("risk_flags")));
                List<String> sourceRefs = dedupeStrings(textList(problemNode.path("source_note_refs")));

                if (shouldHideProblem(name, priority, certainty, problemType, evidence, actions)) {
                    continue;
                }
                if (!StringUtils.hasText(firstProblemName)) {
                    firstProblemName = name;
                }

                allActions.addAll(actions);
                allEvidence.addAll(evidence);
                allSourceRefs.addAll(sourceRefs);
                problemRiskFlags.addAll(risks);

                PatientTimelineViewData.ProblemItem problemItem = new PatientTimelineViewData.ProblemItem();
                problemItem.setName(name);
                problemItem.setProblemKey(normalizeProblemKey(textValue(problemNode, "problem_key"), name, idx));
                problemItem.setProblemType(problemType);
                problemItem.setProblemTypeLabel(problemTypeLabel(problemType));
                problemItem.setStatus(status);
                problemItem.setStatusLabel(statusLabel(status));
                problemItem.setCertainty(certainty);
                problemItem.setCertaintyLabel(certaintyLabel(certainty));
                problemItem.setEvidence(evidence);
                problemItem.setActions(actions);
                problemItem.setRisks(risks);
                problemItem.setSources(parseSourceNotes(sourceRefs));

                boolean primaryHit = isPrimaryProblem(name, priority, certainty, status, problemType);
                boolean riskHit = isRiskProblem(name, certainty, problemType, status);
                boolean secondaryHit = isSecondaryProblem(name, priority, problemType, status);
                String uncertainText = joinList(
                        joinList(name, risks, evidence),
                        Collections.singletonList(certainty),
                        Collections.singletonList(status),
                        Collections.singletonList(problemType)
                );
                if (matchesAny(uncertainText, enumRuleValues(ruleProperties.getImportantUnconfirmedPatterns()))
                        && ("high".equals(priority) || "medium".equals(priority) || riskHit)) {
                    hasImportantUnconfirmed = true;
                }

                if (primaryHit) {
                    primary.add(problemItem);
                    if ("high".equals(priority)) {
                        hasHighPriorityPrimary = true;
                    }
                    continue;
                }
                if (riskHit && !secondaryHit) {
                    riskItems.add(buildRiskItemFromProblem(problemItem));
                    continue;
                }
                if (secondaryHit) {
                    secondary.add(problemItem);
                    continue;
                }
                if (riskHit) {
                    riskItems.add(buildRiskItemFromProblem(problemItem));
                }
            }
        }

        List<String> allRiskFlags = dedupeStrings(mergeLists(dayRiskFlags, problemRiskFlags));
        List<PatientTimelineViewData.SourceNote> sourceNotes = parseSourceNotes(allSourceRefs);
        appendRiskFlags(riskItems, allRiskFlags, sourceNotes, summary);
        riskItems = dedupeRiskItems(riskItems);

        List<String> actionItems = buildActionItems(allActions);
        List<String> evidenceHighlights = limitList(dedupeStrings(allEvidence), 4);

        PatientTimelineViewData.TimelineItem item = new PatientTimelineViewData.TimelineItem();
        item.setDate(date);
        item.setSummary(summary);
        item.setPrimaryProblems(limitList(primary, 2));
        item.setSecondaryProblems(limitList(secondary, 2));
        item.setRiskItems(limitList(riskItems, 3));
        item.setActions(actionItems);
        item.setNextFocus(nextFocus);
        item.setEvidenceHighlights(limitList(evidenceHighlights, 4));
        item.setSourceNotes(sourceNotes);
        item.setBadges(new ArrayList<>());
        item.setSeverity("low");
        item.setSeverityLabel(severityLabel("low"));
        item.setKeyDay(false);

        PatientTimelineViewData.RawRef rawRef = new PatientTimelineViewData.RawRef();
        rawRef.setRecordType(recordType);
        rawRef.setSourceNoteRefs(rawSourceRefs);
        item.setRawRef(rawRef);

        item.setTitle(buildTitle(primary, firstProblemName, summary));
        TimelineBuildContext context = new TimelineBuildContext();
        context.item = item;
        context.date = toSortableDate(item);
        context.summary = summary;
        context.sourceRefs = allSourceRefs;
        context.riskFlags = allRiskFlags;
        context.actionTexts = allActions;
        context.evidenceTexts = allEvidence;
        context.problemNames = extractProblemNames(primary, secondary, riskItems);
        context.hasHighPriorityPrimary = hasHighPriorityPrimary;
        context.hasSecondaryProblem = !secondary.isEmpty();
        context.hasImportantUnconfirmed = hasImportantUnconfirmed;
        return context;
    }

    private JsonNode pickFlatDailyFusion(JsonNode timelineNode) {
        List<JsonNode> candidates = new ArrayList<>();
        candidates.add(timelineNode);
        JsonNode current = timelineNode;
        for (int i = 0; i < 4; i++) {
            JsonNode next = current.path("daily_fusion");
            if (next.isMissingNode() || next.isNull() || !next.isObject()) {
                break;
            }
            candidates.add(next);
            current = next;
        }
        for (int i = candidates.size() - 1; i >= 0; i--) {
            JsonNode node = candidates.get(i);
            if (node.has("problem_list") || node.has("major_actions") || node.has("key_evidence") || node.has("next_focus_24h")) {
                return node;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private String buildTitle(List<PatientTimelineViewData.ProblemItem> primaryProblems, String firstProblemName, String summary) {
        if (primaryProblems != null && !primaryProblems.isEmpty() && StringUtils.hasText(primaryProblems.get(0).getName())) {
            return primaryProblems.get(0).getName();
        }
        if (StringUtils.hasText(firstProblemName)) {
            return firstProblemName;
        }
        if (!StringUtils.hasText(summary)) {
            return "";
        }
        String trimmed = summary.trim();
        return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20) + "…";
    }

    private void applyAutoGenerationRules(List<TimelineBuildContext> contexts) {
        LocalDate admissionDate = findAdmissionDate(contexts);
        LocalDate lastSurgeryDate = null;
        for (TimelineBuildContext context : contexts) {
            LinkedHashSet<String> badges = new LinkedHashSet<>(buildSourceBadges(context, admissionDate));

            if (matchesAny(joinList("", context.sourceRefs), ruleProperties.getSourceSurgeryPatterns())) {
                badges.add("手术日");
            }
            appendPrimaryProblemBadges(context, badges);

            if (badges.contains("手术日") && context.date != null) {
                lastSurgeryDate = context.date;
            }
            if (lastSurgeryDate != null && context.date != null && !context.date.isBefore(lastSurgeryDate)) {
                long postopDays = ChronoUnit.DAYS.between(lastSurgeryDate, context.date);
                if (postopDays == 0) {
                    badges.add("手术日");
                } else {
                    badges.add("术后第" + postopDays + "天");
                }
            }

            String severity = resolveSeverityByRules(context, badges);
            if ("high".equals(severity)) {
                badges.add("高风险");
            }
            context.item.setSeverity(severity);
            context.item.setSeverityLabel(severityLabel(severity));
            context.item.setBadges(limitList(new ArrayList<>(badges), ruleProperties.getMaxBadges()));
            context.item.setKeyDay(resolveKeyDay(context, badges, severity));
        }
    }

    private LocalDate findAdmissionDate(List<TimelineBuildContext> contexts) {
        for (TimelineBuildContext context : contexts) {
            if (context.date != null) {
                return context.date;
            }
        }
        return null;
    }

    private LinkedHashSet<String> buildSourceBadges(TimelineBuildContext context, LocalDate admissionDate) {
        LinkedHashSet<String> badges = new LinkedHashSet<>();
        String sourceText = joinList("", context.sourceRefs);
        if (matchesAny(sourceText, ruleProperties.getSourceSurgeryPatterns())) {
            badges.add("手术日");
        }
        if (matchesAny(sourceText, ruleProperties.getSourceConsultPatterns())) {
            badges.add("会诊");
        }
        if (matchesAny(sourceText, ruleProperties.getSourceDischargePatterns())) {
            badges.add("拟出院");
        }
        if (matchesAny(sourceText, ruleProperties.getSourceAdmissionPatterns())
                && context.date != null
                && admissionDate != null
                && context.date.equals(admissionDate)) {
            badges.add("入院日");
        }
        if (context.date != null
                && admissionDate != null
                && context.date.equals(admissionDate)
                && matchesAny(context.summary, ruleProperties.getAdmissionSummaryPatterns())) {
            badges.add("入院日");
        }
        return badges;
    }

    private void appendPrimaryProblemBadges(TimelineBuildContext context, LinkedHashSet<String> badges) {
        int added = 0;
        for (PatientTimelineViewData.ProblemItem problem : context.item.getPrimaryProblems()) {
            String name = problem.getName();
            for (TimelineViewRuleProperties.PrimaryProblemBadgeRule rule : safeList(ruleProperties.getPrimaryProblemBadgeRules())) {
                if (!StringUtils.hasText(rule.getPattern()) || !StringUtils.hasText(rule.getBadge())) {
                    continue;
                }
                if (matchesAny(name, Collections.singletonList(rule.getPattern())) && !badges.contains(rule.getBadge())) {
                    badges.add(rule.getBadge());
                    added++;
                    if (added >= 2) {
                        return;
                    }
                }
            }
        }
        if (matchesAny(context.summary, ruleProperties.getDischargeSummaryPatterns()) && !badges.contains("拟出院")) {
            badges.add("拟出院");
        }
    }

    private String resolveSeverityByRules(TimelineBuildContext context, Set<String> badges) {
        boolean highByBadge = badges.stream().anyMatch(b -> containsInList(b, ruleProperties.getHighBadges()))
                || badges.stream().anyMatch(b -> startsWithAny(b, ruleProperties.getHighPostOpBadgePrefixes()));
        if (highByBadge || context.hasHighPriorityPrimary) {
            return "high";
        }
        if (context.hasSecondaryProblem
                || badges.stream().anyMatch(b -> containsInList(b, ruleProperties.getMediumBadges()))
                || context.hasImportantUnconfirmed) {
            return "medium";
        }
        return "low";
    }

    private boolean resolveKeyDay(TimelineBuildContext context, Set<String> badges, String severity) {
        for (String badge : badges) {
            if (containsInList(badge, ruleProperties.getKeyDaySourceBadges())
                    || startsWithAny(badge, ruleProperties.getHighPostOpBadgePrefixes())) {
                return true;
            }
        }
        return "high".equals(severity);
    }

    private boolean isPrimaryProblem(String name, String priority, String certainty, String status, String problemType) {
        boolean statusMatch = containsInList(status, ruleProperties.getPrimaryStatuses());
        return "high".equals(priority)
                && "confirmed".equals(certainty)
                && statusMatch
                && !"risk_state".equals(problemType)
                && !"differential".equals(problemType);
    }

    private boolean isSecondaryProblem(String name, String priority, String problemType, String status) {
        if ("medium".equals(priority)) {
            return true;
        }
        if ("complication".equals(problemType)) {
            return true;
        }
        return "chronic".equals(problemType) && !"unclear".equals(status);
    }

    private boolean isRiskProblem(String name, String certainty, String problemType, String status) {
        return "risk_state".equals(problemType) || "risk_only".equals(certainty);
    }

    private boolean shouldHideProblem(String name,
                                      String priority,
                                      String certainty,
                                      String problemType,
                                      List<String> evidence,
                                      List<String> actions) {
        if (containsAny(problemType, enumRuleValues(ruleProperties.getHideProblemTypePatterns()))) {
            return true;
        }
        return containsAny(name, ruleProperties.getHideProblemNamePatterns());
    }

    private PatientTimelineViewData.RiskItem buildRiskItemFromProblem(PatientTimelineViewData.ProblemItem item) {
        PatientTimelineViewData.RiskItem riskItem = new PatientTimelineViewData.RiskItem();
        riskItem.setName(item.getName());
        riskItem.setBasis(firstNonBlank(firstOrEmpty(item.getEvidence()), firstOrEmpty(item.getRisks())));
        riskItem.setSources(item.getSources());
        return riskItem;
    }

    private void appendRiskFlags(List<PatientTimelineViewData.RiskItem> riskItems,
                                 List<String> riskFlags,
                                 List<PatientTimelineViewData.SourceNote> sourceNotes,
                                 String summary) {
        for (String flag : riskFlags) {
            if (!StringUtils.hasText(flag)) {
                continue;
            }
            PatientTimelineViewData.RiskItem riskItem = new PatientTimelineViewData.RiskItem();
            riskItem.setName(flag);
            riskItem.setBasis(firstNonBlank(summary, flag));
            riskItem.setSources(sourceNotes);
            riskItems.add(riskItem);
        }
    }

    private List<String> buildActionItems(List<String> actions) {
        return limitList(dedupeStrings(actions), 8);
    }

    private List<PatientTimelineViewData.SourceNote> parseSourceNotes(List<String> refs) {
        LinkedHashMap<String, PatientTimelineViewData.SourceNote> map = new LinkedHashMap<>();
        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            String raw = ref.trim();
            String name = raw;
            String time = "";
            int idx = raw.lastIndexOf('@');
            if (idx > 0 && idx < raw.length() - 1) {
                name = raw.substring(0, idx).trim();
                time = raw.substring(idx + 1).trim();
            }
            PatientTimelineViewData.SourceNote sourceNote = new PatientTimelineViewData.SourceNote();
            sourceNote.setName(name);
            sourceNote.setTime(time);
            map.putIfAbsent(name + "@" + time, sourceNote);
        }
        return new ArrayList<>(map.values());
    }

    private List<PatientTimelineViewData.RiskItem> dedupeRiskItems(List<PatientTimelineViewData.RiskItem> raw) {
        LinkedHashMap<String, PatientTimelineViewData.RiskItem> map = new LinkedHashMap<>();
        for (PatientTimelineViewData.RiskItem item : raw) {
            if (item == null || !StringUtils.hasText(item.getName())) {
                continue;
            }
            map.putIfAbsent(item.getName().trim(), item);
        }
        return new ArrayList<>(map.values());
    }

    private List<String> extractProblemNames(List<PatientTimelineViewData.ProblemItem> primary,
                                             List<PatientTimelineViewData.ProblemItem> secondary,
                                             List<PatientTimelineViewData.RiskItem> risks) {
        List<String> names = new ArrayList<>();
        for (PatientTimelineViewData.ProblemItem item : primary) {
            if (StringUtils.hasText(item.getName())) {
                names.add(item.getName());
            }
        }
        for (PatientTimelineViewData.ProblemItem item : secondary) {
            if (StringUtils.hasText(item.getName())) {
                names.add(item.getName());
            }
        }
        for (PatientTimelineViewData.RiskItem item : risks) {
            if (StringUtils.hasText(item.getName())) {
                names.add(item.getName());
            }
        }
        return dedupeStrings(names);
    }

    private String joinList(String first, List<String>... lists) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(first)) {
            sb.append(first.trim());
        }
        for (List<String> list : lists) {
            if (list == null) {
                continue;
            }
            for (String value : list) {
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(value.trim());
            }
        }
        return sb.toString();
    }

    private boolean matchesAny(String text, List<String> patterns) {
        if (!StringUtils.hasText(text) || patterns == null || patterns.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String regex : patterns) {
            if (!StringUtils.hasText(regex)) {
                continue;
            }
            if (compilePattern(regex).matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }

    private List<String> textList(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(child -> {
                String val = child == null ? "" : child.asText("");
                if (StringUtils.hasText(val)) {
                    result.add(val.trim());
                }
            });
            return result;
        }
        String val = node.asText("");
        if (StringUtils.hasText(val)) {
            result.add(val.trim());
        }
        return result;
    }

    private String textValue(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return "";
        }
        return value.asText("").trim();
    }

    private String normalizeLower(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDate(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String v = value.trim();
        return v.length() >= 10 ? v.substring(0, 10) : v;
    }

    private LocalDate toSortableDate(PatientTimelineViewData.TimelineItem item) {
        if (item == null || !StringUtils.hasText(item.getDate())) {
            return null;
        }
        try {
            return LocalDate.parse(item.getDate().substring(0, Math.min(10, item.getDate().length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String normalizeProblemKey(String key, String name, int index) {
        if (!StringUtils.hasText(key)) {
            return generateProblemKey(name, index);
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_\\-\\u4e00-\\u9fa5]+")) {
            return generateProblemKey(name, index);
        }
        return normalized;
    }

    private String generateProblemKey(String name, int index) {
        if (!StringUtils.hasText(name)) {
            return "problem_" + index;
        }
        String normalized = name.trim()
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);
        return StringUtils.hasText(normalized) ? normalized : "problem_" + index;
    }

    private boolean startsWithAny(String text, List<String> prefixes) {
        if (!StringUtils.hasText(text) || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (StringUtils.hasText(prefix) && text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String source, String... keywords) {
        if (!StringUtils.hasText(source)) {
            return false;
        }
        String text = source.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String source, List<String> keywords) {
        if (!StringUtils.hasText(source) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        return containsAny(source, keywords.toArray(new String[0]));
    }

    private boolean containsInList(String value, List<String> list) {
        if (!StringUtils.hasText(value) || list == null || list.isEmpty()) {
            return false;
        }
        return list.contains(value);
    }

    private List<String> enumRuleValues(List<TimelineViewRuleProperties.EnumLabelRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (TimelineViewRuleProperties.EnumLabelRule rule : rules) {
            if (rule != null && StringUtils.hasText(rule.getValue())) {
                values.add(rule.getValue().trim());
            }
        }
        return values;
    }

    private String statusLabel(String value) {
        return labelOf(getStatusLabelMap(), value);
    }

    private String certaintyLabel(String value) {
        return labelOf(getCertaintyLabelMap(), value);
    }

    private String severityLabel(String value) {
        return labelOf(getSeverityLabelMap(), value);
    }

    private String problemTypeLabel(String value) {
        return labelOf(getProblemTypeLabelMap(), value);
    }

    private String labelOf(Map<String, String> labelMap, String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return labelMap.getOrDefault(value.trim().toLowerCase(Locale.ROOT), value.trim());
    }

    private Map<String, String> getStatusLabelMap() {
        Map<String, String> current = statusLabelMap;
        if (current == null) {
            current = buildEnumLabelMap(ruleProperties.getStatusLabelRules());
            statusLabelMap = current;
        }
        return current;
    }

    private Map<String, String> getCertaintyLabelMap() {
        Map<String, String> current = certaintyLabelMap;
        if (current == null) {
            current = buildEnumLabelMap(ruleProperties.getCertaintyLabelRules());
            certaintyLabelMap = current;
        }
        return current;
    }

    private Map<String, String> getSeverityLabelMap() {
        Map<String, String> current = severityLabelMap;
        if (current == null) {
            current = buildEnumLabelMap(ruleProperties.getSeverityLabelRules());
            severityLabelMap = current;
        }
        return current;
    }

    private Map<String, String> getProblemTypeLabelMap() {
        Map<String, String> current = problemTypeLabelMap;
        if (current == null) {
            current = buildEnumLabelMap(ruleProperties.getProblemTypeLabelRules());
            problemTypeLabelMap = current;
        }
        return current;
    }

    private Map<String, String> buildEnumLabelMap(List<TimelineViewRuleProperties.EnumLabelRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (TimelineViewRuleProperties.EnumLabelRule rule : rules) {
            if (rule == null || !StringUtils.hasText(rule.getValue()) || !StringUtils.hasText(rule.getLabel())) {
                continue;
            }
            map.put(rule.getValue().trim().toLowerCase(Locale.ROOT), rule.getLabel().trim());
        }
        return map;
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private Pattern compilePattern(String regex) {
        return patternCache.computeIfAbsent(regex, key -> Pattern.compile(key, Pattern.CASE_INSENSITIVE));
    }

    private List<String> mergeLists(List<String> a, List<String> b) {
        List<String> merged = new ArrayList<>();
        if (a != null) {
            merged.addAll(a);
        }
        if (b != null) {
            merged.addAll(b);
        }
        return merged;
    }

    private List<String> dedupeStrings(List<String> values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    set.add(value.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    private String firstOrEmpty(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return list.get(0);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private <T> List<T> limitList(List<T> list, int limit) {
        if (list == null || list.size() <= limit) {
            return list == null ? new ArrayList<>() : list;
        }
        return new ArrayList<>(list.subList(0, limit));
    }

    private static class TimelineBuildContext {
        private PatientTimelineViewData.TimelineItem item;
        private LocalDate date;
        private int pageOrder;
        private String summary;
        private List<String> sourceRefs = new ArrayList<>();
        private List<String> riskFlags = new ArrayList<>();
        private List<String> actionTexts = new ArrayList<>();
        private List<String> evidenceTexts = new ArrayList<>();
        private List<String> problemNames = new ArrayList<>();
        private boolean hasHighPriorityPrimary;
        private boolean hasSecondaryProblem;
        private boolean hasImportantUnconfirmed;
    }
}
