package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.entity.InfectionCaseSnapshotEntity;
import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionCaseState;
import com.zzhy.yg_ai.domain.enums.InfectionEvidenceRole;
import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.InfectionJudgeContext;
import com.zzhy.yg_ai.domain.model.InfectionJudgePrecompute;
import com.zzhy.yg_ai.domain.model.InfectionRecentChanges;
import com.zzhy.yg_ai.domain.model.JudgeBackgroundSummary;
import com.zzhy.yg_ai.domain.model.JudgeCatalogEvent;
import com.zzhy.yg_ai.domain.model.JudgeDecisionBuckets;
import com.zzhy.yg_ai.domain.model.JudgeEvidenceGroup;
import com.zzhy.yg_ai.service.InfectionCaseSnapshotService;
import com.zzhy.yg_ai.service.InfectionEvidencePacketBuilder;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfectionEvidencePacketBuilderImpl implements InfectionEvidencePacketBuilder {

    private static final int PACKET_VERSION = 2;
    private static final int MAX_GROUPS = 12;
    private static final int MAX_CHANGES = 8;
    private static final int MAX_CONTEXT_ITEMS = 5;
    private static final int MAX_BACKGROUND_EXAMPLES = 2;

    private final InfectionEventPoolService infectionEventPoolService;
    private final InfectionCaseSnapshotService infectionCaseSnapshotService;
    private final PatientService patientService;
    private final ObjectMapper objectMapper;

    @Override
    public InfectionEvidencePacket build(String reqno, LocalDateTime judgeTime) {
        if (!StringUtils.hasText(reqno)) {
            return InfectionEvidencePacket.builder()
                    .decisionBuckets(emptyBuckets())
                    .backgroundSummary(emptyBackgroundSummary())
                    .build();
        }

        String safeReqno = reqno.trim();
        InfectionCaseSnapshotEntity snapshot = infectionCaseSnapshotService.getOrInit(safeReqno);
        List<InfectionEventPoolEntity> activeEvents = listActiveEvents(safeReqno);
        long lastVersion = snapshot == null || snapshot.getLastEventPoolVersion() == null ? 0L : snapshot.getLastEventPoolVersion();
        long latestVersion = activeEvents.stream()
                .map(InfectionEventPoolEntity::getId)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(lastVersion);

        List<JudgeCatalogEvent> catalog = buildCatalog(activeEvents, lastVersion);
        Map<String, JudgeCatalogEvent> catalogById = catalog.stream()
                .collect(Collectors.toMap(JudgeCatalogEvent::eventId, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<JudgeCatalogEvent> primaryEvents = catalog.stream()
                .filter(item -> !isBackground(item))
                .toList();
        List<JudgeCatalogEvent> backgroundEvents = catalog.stream()
                .filter(this::isBackground)
                .toList();

        List<JudgeEvidenceGroup> allGroups = buildGroups(primaryEvents);
        List<JudgeEvidenceGroup> selectedGroups = allGroups.stream()
                .sorted(groupComparator())
                .limit(MAX_GROUPS)
                .toList();

        Set<String> selectedEventIds = selectedGroups.stream()
                .map(JudgeEvidenceGroup::memberEventIds)
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<JudgeCatalogEvent> selectedCatalog = catalog.stream()
                .filter(item -> selectedEventIds.contains(item.eventId()))
                .toList();
        Map<String, JudgeCatalogEvent> selectedCatalogById = selectedCatalog.stream()
                .collect(Collectors.toMap(JudgeCatalogEvent::eventId, item -> item, (left, right) -> left, LinkedHashMap::new));

        LocalDate anchorDate = judgeTime == null ? LocalDate.now() : judgeTime.toLocalDate();
        return InfectionEvidencePacket.builder()
                .reqno(safeReqno)
                .anchorTime(judgeTime)
                .packetVersion(PACKET_VERSION)
                .snapshotVersion(snapshot == null ? 0 : snapshot.getLastResultVersion())
                .eventPoolVersion(latestVersion)
                .caseState(snapshot == null ? InfectionCaseState.NO_RISK.code() : snapshot.getCaseState())
                .recentChanges(extractRecentChanges(safeReqno, anchorDate))
                .eventCatalog(selectedCatalog)
                .evidenceGroups(selectedGroups)
                .decisionBuckets(buildBuckets(selectedGroups))
                .backgroundSummary(buildBackgroundSummary(backgroundEvents))
                .judgeContext(buildJudgeContext(selectedGroups, selectedCatalogById))
                .precomputed(buildPrecompute(safeReqno, anchorDate, allGroups, catalogById))
                .build();
    }

    private List<InfectionEventPoolEntity> listActiveEvents(String reqno) {
        List<InfectionEventPoolEntity> activeEvents = infectionEventPoolService.listActiveEvents(reqno);
        if (activeEvents == null || activeEvents.isEmpty()) {
            return List.of();
        }
        return activeEvents.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(this::resolveEventTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(InfectionEventPoolEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<JudgeCatalogEvent> buildCatalog(List<InfectionEventPoolEntity> entities, long lastVersion) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<JudgeCatalogEvent> result = new ArrayList<>(entities.size());
        int index = 1;
        for (InfectionEventPoolEntity entity : entities) {
            Map<String, String> attrs = parseAttributes(entity == null ? null : entity.getAttributesJson());
            String eventName = trimToNull(entity == null ? null : entity.getTitle());
            String summaryText = trimToNull(eventName != null ? eventName : entity == null ? null : entity.getContent());
            result.add(JudgeCatalogEvent.builder()
                    .eventId("E" + index++)
                    .eventKey(entity == null ? null : entity.getEventKey())
                    .isNew(entity != null && entity.getId() != null && entity.getId() > lastVersion)
                    .eventTime(resolveEventTime(entity))
                    .eventType(trimToNull(entity == null ? null : entity.getEventType()))
                    .eventSubtype(trimToNull(entity == null ? null : entity.getEventSubtype()))
                    .bodySite(trimToNull(entity == null ? null : entity.getSite()))
                    .eventName(eventName)
                    .clinicalMeaning(trimToNull(attrs.get("clinical_meaning")))
                    .evidenceTier(attrs.get("evidence_tier"))
                    .evidenceRole(attrs.get("evidence_role"))
                    .sourceKind(resolveSourceKind(entity))
                    .summaryText(summaryText)
                    .build());
        }
        return List.copyOf(result);
    }

    private List<JudgeEvidenceGroup> buildGroups(List<JudgeCatalogEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Map<String, GroupSeed> grouped = new LinkedHashMap<>();
        for (JudgeCatalogEvent event : events) {
            GroupSeed seed = grouped.computeIfAbsent(groupKey(event), key -> new GroupSeed());
            seed.events.add(event);
        }

        List<JudgeEvidenceGroup> result = new ArrayList<>(grouped.size());
        int index = 1;
        for (GroupSeed seed : grouped.values()) {
            JudgeCatalogEvent representative = selectRepresentative(seed.events);
            if (representative == null) {
                continue;
            }
            LocalDateTime earliest = seed.events.stream()
                    .map(JudgeCatalogEvent::eventTime)
                    .filter(Objects::nonNull)
                    .min(LocalDateTime::compareTo)
                    .orElse(null);
            LocalDateTime latest = seed.events.stream()
                    .map(JudgeCatalogEvent::eventTime)
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            List<String> memberEventIds = seed.events.stream()
                    .map(JudgeCatalogEvent::eventId)
                    .filter(StringUtils::hasText)
                    .toList();
            List<String> sourceKinds = seed.events.stream()
                    .map(JudgeCatalogEvent::sourceKind)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            boolean isNew = seed.events.stream()
                    .anyMatch(event -> Boolean.TRUE.equals(event.isNew()));

            result.add(JudgeEvidenceGroup.builder()
                    .groupId("G" + index++)
                    .eventType(representative.eventType())
                    .eventSubtype(representative.eventSubtype())
                    .bodySite(defaultIfBlank(representative.bodySite(), "unknown"))
                    .evidenceRole(representative.evidenceRole())
                    .clinicalMeaning(representative.clinicalMeaning())
                    .earliestTime(earliest)
                    .latestTime(latest)
                    .representativeEventId(representative.eventId())
                    .representativeEventKey(representative.eventKey())
                    .memberEventIds(memberEventIds)
                    .sourceKinds(sourceKinds)
                    .maxEvidenceTier(maxEvidenceTier(seed.events))
                    .isNew(isNew)
                    .summaryText(representative.summaryText())
                    .build());
        }
        return List.copyOf(result);
    }

    private JudgeDecisionBuckets buildBuckets(List<JudgeEvidenceGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return emptyBuckets();
        }
        List<String> newGroupIds = new ArrayList<>();
        List<String> supportGroupIds = new ArrayList<>();
        List<String> againstGroupIds = new ArrayList<>();
        List<String> riskGroupIds = new ArrayList<>();
        for (JudgeEvidenceGroup group : groups) {
            if (Boolean.TRUE.equals(group.isNew())) {
                newGroupIds.add(group.groupId());
            }
            if (isSupport(group)) {
                supportGroupIds.add(group.groupId());
            }
            if (isAgainst(group)) {
                againstGroupIds.add(group.groupId());
            }
            if (isRisk(group)) {
                riskGroupIds.add(group.groupId());
            }
        }
        return JudgeDecisionBuckets.builder()
                .newGroupIds(newGroupIds)
                .supportGroupIds(supportGroupIds)
                .againstGroupIds(againstGroupIds)
                .riskGroupIds(riskGroupIds)
                .build();
    }

    private JudgeBackgroundSummary buildBackgroundSummary(List<JudgeCatalogEvent> backgroundEvents) {
        if (backgroundEvents == null || backgroundEvents.isEmpty()) {
            return emptyBackgroundSummary();
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (JudgeCatalogEvent event : backgroundEvents) {
            byType.merge(defaultIfBlank(event.eventType(), "unknown"), 1, Integer::sum);
        }
        List<String> examples = backgroundEvents.stream()
                .map(JudgeCatalogEvent::summaryText)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(MAX_BACKGROUND_EXAMPLES)
                .toList();
        return JudgeBackgroundSummary.builder()
                .backgroundCount(backgroundEvents.size())
                .backgroundByType(byType)
                .backgroundExamples(examples)
                .build();
    }

    private InfectionRecentChanges extractRecentChanges(String reqno, LocalDate anchorDate) {
        String summaryJson = patientService.buildSummaryWindowJson(reqno, anchorDate);
        if (!StringUtils.hasText(summaryJson)) {
            return InfectionRecentChanges.builder().changes(List.of()).build();
        }
        try {
            JsonNode root = objectMapper.readTree(summaryJson);
            List<String> changes = new ArrayList<>();
            JsonNode changeNode = root.path("changes");
            if (changeNode.isArray()) {
                changeNode.forEach(node -> {
                    String text = node == null ? null : node.asText("");
                    if (StringUtils.hasText(text) && changes.size() < MAX_CHANGES) {
                        changes.add(text.trim());
                    }
                });
            }
            return InfectionRecentChanges.builder().changes(changes).build();
        } catch (Exception e) {
            log.warn("构造 InfectionEvidencePacket recentChanges 失败, reqno={}", reqno, e);
            return InfectionRecentChanges.builder().changes(List.of()).build();
        }
    }

    private InfectionJudgeContext buildJudgeContext(List<JudgeEvidenceGroup> groups, Map<String, JudgeCatalogEvent> catalogById) {
        if (groups == null || groups.isEmpty()) {
            return InfectionJudgeContext.builder().build();
        }
        List<String> recentOperations = groups.stream()
                .filter(group -> "procedure".equals(group.eventType()) || "procedure_exposure".equals(group.eventSubtype()))
                .map(group -> toContextText(group, catalogById))
                .filter(StringUtils::hasText)
                .limit(MAX_CONTEXT_ITEMS)
                .toList();
        List<String> recentDevices = groups.stream()
                .filter(group -> "device".equals(group.eventType()) || "device_exposure".equals(group.eventSubtype()))
                .map(group -> toContextText(group, catalogById))
                .filter(StringUtils::hasText)
                .limit(MAX_CONTEXT_ITEMS)
                .toList();
        List<String> recentAntibiotics = groups.stream()
                .filter(group -> "antibiotic_started".equals(group.eventSubtype()) || "antibiotic_upgraded".equals(group.eventSubtype()))
                .map(group -> toContextText(group, catalogById))
                .filter(StringUtils::hasText)
                .limit(MAX_CONTEXT_ITEMS)
                .toList();
        List<String> majorSites = groups.stream()
                .map(JudgeEvidenceGroup::bodySite)
                .filter(StringUtils::hasText)
                .filter(site -> !"unknown".equals(site))
                .collect(Collectors.groupingBy(site -> site, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(3)
                .toList();
        return InfectionJudgeContext.builder()
                .recentOperations(recentOperations)
                .recentDevices(recentDevices)
                .recentAntibiotics(recentAntibiotics)
                .majorSites(majorSites)
                .build();
    }

    private InfectionJudgePrecompute buildPrecompute(String reqno,
                                                     LocalDate anchorDate,
                                                     List<JudgeEvidenceGroup> groups,
                                                     Map<String, JudgeCatalogEvent> catalogById) {
        List<JudgeEvidenceGroup> newGroups = groups.stream().filter(group -> Boolean.TRUE.equals(group.isNew())).toList();
        List<JudgeEvidenceGroup> supportGroups = groups.stream().filter(this::isSupport).toList();
        List<JudgeEvidenceGroup> riskGroups = groups.stream().filter(this::isRisk).toList();

        boolean newOnsetFlag = newGroups.stream().anyMatch(this::isSignalGroup);
        String after48hFlag = resolveAfter48hFlag(reqno, newGroups, supportGroups, riskGroups);
        boolean procedureRelatedFlag = hasRiskAssociation("procedure_exposure", riskGroups, supportGroups, newGroups);
        boolean deviceRelatedFlag = hasRiskAssociation("device_exposure", riskGroups, supportGroups, newGroups);

        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("new_onset", newOnsetFlag);
        reasons.put("after_48h", after48hFlag);
        reasons.put("procedure_related", procedureRelatedFlag);
        reasons.put("device_related", deviceRelatedFlag);
        reasons.put("reference_event_keys", resolveReferenceKeys(newGroups, supportGroups, riskGroups, catalogById));

        return InfectionJudgePrecompute.builder()
                .newOnsetFlag(newOnsetFlag)
                .after48hFlag(after48hFlag)
                .procedureRelatedFlag(procedureRelatedFlag)
                .deviceRelatedFlag(deviceRelatedFlag)
                .precomputeReasonJson(writeJson(reasons))
                .build();
    }

    private String resolveAfter48hFlag(String reqno,
                                       List<JudgeEvidenceGroup> newGroups,
                                       List<JudgeEvidenceGroup> supportGroups,
                                       List<JudgeEvidenceGroup> riskGroups) {
        LocalDateTime admissionTime = resolveAdmissionTime(reqno);
        if (admissionTime == null) {
            return "unknown";
        }
        LocalDateTime referenceTime = earliestGroupTime(newGroups);
        if (referenceTime == null) {
            referenceTime = earliestGroupTime(supportGroups);
        }
        if (referenceTime == null) {
            referenceTime = earliestGroupTime(riskGroups);
        }
        if (referenceTime == null) {
            return "unknown";
        }
        return referenceTime.isBefore(admissionTime.plusHours(48)) ? "false" : "true";
    }

    private LocalDateTime resolveAdmissionTime(String reqno) {
        PatientRawDataEntity rawData = patientService.getFirstRawDataByReqno(reqno);
        if (rawData == null) {
            return null;
        }
        LocalDateTime fromFilter = extractAdmissionTime(rawData.getFilterDataJson());
        return fromFilter != null ? fromFilter : extractAdmissionTime(rawData.getDataJson());
    }

    private LocalDateTime extractAdmissionTime(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String raw = root.path("admission_time").asText("");
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            for (var formatter : DateTimeUtils.DATE_TIME_PARSE_FORMATTERS) {
                try {
                    return DateTimeUtils.truncateToMillis(LocalDateTime.parse(raw.trim(), formatter));
                } catch (DateTimeParseException ignore) {
                    // try next formatter
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("解析 admission_time 失败", e);
            return null;
        }
    }

    private boolean hasRiskAssociation(String riskSubtype,
                                       List<JudgeEvidenceGroup> riskGroups,
                                       List<JudgeEvidenceGroup> supportGroups,
                                       List<JudgeEvidenceGroup> newGroups) {
        boolean hasRisk = riskGroups.stream().anyMatch(group -> riskSubtype.equals(group.eventSubtype()));
        return hasRisk && (!supportGroups.isEmpty() || newGroups.stream().anyMatch(this::isSupport));
    }

    private boolean isSignalGroup(JudgeEvidenceGroup group) {
        return group != null && (isSupport(group) || isAgainst(group) || isRisk(group));
    }

    private boolean isSupport(JudgeEvidenceGroup group) {
        return group != null && InfectionEvidenceRole.SUPPORT.code().equals(group.evidenceRole());
    }

    private boolean isAgainst(JudgeEvidenceGroup group) {
        return group != null && InfectionEvidenceRole.AGAINST.code().equals(group.evidenceRole());
    }

    private boolean isRisk(JudgeEvidenceGroup group) {
        return group != null && InfectionEvidenceRole.RISK_ONLY.code().equals(group.evidenceRole());
    }

    private boolean isBackground(JudgeCatalogEvent event) {
        return event != null && InfectionEvidenceRole.BACKGROUND.code().equals(event.evidenceRole());
    }

    private Comparator<JudgeEvidenceGroup> groupComparator() {
        return Comparator
                .comparing((JudgeEvidenceGroup group) -> Boolean.TRUE.equals(group.isNew()), Comparator.reverseOrder())
                .thenComparing(this::groupRoleRank)
                .thenComparing(group -> evidenceTierRank(group.maxEvidenceTier()), Comparator.reverseOrder())
                .thenComparing(JudgeEvidenceGroup::latestTime, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private int groupRoleRank(JudgeEvidenceGroup group) {
        if (isSupport(group)) {
            return 0;
        }
        if (isAgainst(group)) {
            return 1;
        }
        if (isRisk(group)) {
            return 2;
        }
        return 3;
    }

    private JudgeCatalogEvent selectRepresentative(List<JudgeCatalogEvent> events) {
        return events.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing((JudgeCatalogEvent event) -> evidenceTierRank(event.evidenceTier()), Comparator.reverseOrder())
                        .thenComparing(this::sourceKindRank)
                        .thenComparing(JudgeCatalogEvent::eventTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(event -> defaultIfBlank(event.summaryText(), "~")))
                .findFirst()
                .orElse(null);
    }

    private int sourceKindRank(JudgeCatalogEvent event) {
        if (event == null) {
            return 3;
        }
        return switch (defaultIfBlank(event.sourceKind(), "")) {
            case "clinical_text" -> 0;
            case "structured_fact" -> 1;
            case "mid_semantic" -> 2;
            default -> 3;
        };
    }

    private int evidenceTierRank(String value) {
        return switch (defaultIfBlank(value, "")) {
            case "hard" -> 3;
            case "moderate" -> 2;
            case "weak" -> 1;
            default -> 0;
        };
    }

    private String maxEvidenceTier(List<JudgeCatalogEvent> events) {
        return events.stream()
                .map(JudgeCatalogEvent::evidenceTier)
                .max(Comparator.comparingInt(this::evidenceTierRank))
                .orElse(null);
    }

    private String groupKey(JudgeCatalogEvent event) {
        String concept = normalizeConcept(defaultIfBlank(event.eventName(), defaultIfBlank(event.eventSubtype(), event.eventType())));
        String eventDate = event.eventTime() == null ? "unknown" : event.eventTime().toLocalDate().toString();
        return String.join("|",
                defaultIfBlank(event.eventType(), ""),
                defaultIfBlank(event.eventSubtype(), ""),
                defaultIfBlank(event.bodySite(), "unknown"),
                defaultIfBlank(event.evidenceRole(), ""),
                defaultIfBlank(event.clinicalMeaning(), ""),
                concept,
                eventDate);
    }

    private String normalizeConcept(String value) {
        return defaultIfBlank(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private String resolveSourceKind(InfectionEventPoolEntity entity) {
        String category = trimToNull(entity == null ? null : entity.getEventCategory());
        if ("fact".equals(category)) {
            return "structured_fact";
        }
        if ("text".equals(category)) {
            return "clinical_text";
        }
        if ("semantic".equals(category)) {
            return "mid_semantic";
        }
        return defaultIfBlank(category, "other");
    }

    private String toContextText(JudgeEvidenceGroup group, Map<String, JudgeCatalogEvent> catalogById) {
        if (group == null) {
            return null;
        }
        JudgeCatalogEvent event = catalogById.get(group.representativeEventId());
        String time = group.latestTime() == null ? null : group.latestTime().toLocalDate().toString();
        String text = event == null ? group.summaryText() : event.summaryText();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return StringUtils.hasText(time) ? time + " " + text : text;
    }

    private LocalDateTime earliestGroupTime(List<JudgeEvidenceGroup> groups) {
        return groups.stream()
                .map(JudgeEvidenceGroup::earliestTime)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    private List<String> resolveReferenceKeys(List<JudgeEvidenceGroup> newGroups,
                                              List<JudgeEvidenceGroup> supportGroups,
                                              List<JudgeEvidenceGroup> riskGroups,
                                              Map<String, JudgeCatalogEvent> catalogById) {
        List<JudgeEvidenceGroup> ordered = new ArrayList<>();
        ordered.addAll(newGroups);
        ordered.addAll(supportGroups);
        ordered.addAll(riskGroups);
        return ordered.stream()
                .map(group -> resolveRepresentativeKey(group, catalogById))
                .filter(StringUtils::hasText)
                .distinct()
                .limit(6)
                .toList();
    }

    private String resolveRepresentativeKey(JudgeEvidenceGroup group, Map<String, JudgeCatalogEvent> catalogById) {
        if (group == null) {
            return null;
        }
        JudgeCatalogEvent event = catalogById.get(group.representativeEventId());
        return event == null ? group.representativeEventKey() : event.eventKey();
    }

    private Map<String, String> parseAttributes(String attributesJson) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(attributesJson)) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(attributesJson);
            putIfText(result, "clinical_meaning", root.path("clinical_meaning").asText(""));
            putIfText(result, "evidence_tier", root.path("evidence_tier").asText(""));
            putIfText(result, "evidence_role", root.path("evidence_role").asText(""));
            return result;
        } catch (Exception e) {
            log.debug("解析 infection_event_pool.attributesJson 失败", e);
            return result;
        }
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim().toLowerCase(Locale.ROOT));
        }
    }

    private LocalDateTime resolveEventTime(InfectionEventPoolEntity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getEventTime() != null) {
            return entity.getEventTime();
        }
        if (entity.getDetectedTime() != null) {
            return entity.getDetectedTime();
        }
        return entity.getIngestTime();
    }

    private JudgeDecisionBuckets emptyBuckets() {
        return JudgeDecisionBuckets.builder().build();
    }

    private JudgeBackgroundSummary emptyBackgroundSummary() {
        return JudgeBackgroundSummary.builder()
                .backgroundCount(0)
                .backgroundByType(Map.of())
                .backgroundExamples(List.of())
                .build();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class GroupSeed {
        private final List<JudgeCatalogEvent> events = new ArrayList<>();
    }
}
