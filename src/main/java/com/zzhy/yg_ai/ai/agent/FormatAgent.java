package com.zzhy.yg_ai.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.prompt.FormatAgentPrompt;
import com.zzhy.yg_ai.ai.prompt.SummaryAgentPrompt;
import com.zzhy.yg_ai.common.FilterTextUtils;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import com.zzhy.yg_ai.domain.model.PatientContext;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class FormatAgent extends AbstractAgent {

    private final ObjectMapper objectMapper;
    private final FilterTextUtils filterTextUtils;

    public PatientContext format(String rawDataJson, String inhosdateRawJson) {
        String input = StringUtils.hasText(rawDataJson) ? rawDataJson : "{}";
        String inhosdateJson = StringUtils.hasText(inhosdateRawJson) ? inhosdateRawJson : "{}";
        try {
            Map<String, String> splitInput = AgentUtils.splitInput(input);
            String illnessJson = splitInput.get("illnessJson");
            String otherJson = splitInput.get("otherJson");

            Map<String, String> splitInhosdate = AgentUtils.splitInput(inhosdateJson);
            String InhosdateIllnessJson = splitInhosdate.get("illnessJson");

            illnessJson = AgentUtils.removeDuplicateSentences(InhosdateIllnessJson, illnessJson);
            final String finalIllnessJson = illnessJson;
            final String finalOtherJson = otherJson;
            boolean hasFirstIllnessCourse = IllnessRecordType.FIRST_COURSE.matches(finalIllnessJson);

            CompletableFuture<String> illnessFuture =
                    CompletableFuture.supplyAsync(() -> formatIllnessSection(finalIllnessJson));
            CompletableFuture<String> otherFuture =
                    CompletableFuture.supplyAsync(() -> formatOtherSection(finalOtherJson));
            CompletableFuture<String> finalOutputFuture = illnessFuture.thenCombineAsync(
                    otherFuture,
                    (illnessPart, otherPart) -> {
                        String finalInput = AgentUtils.toJson(AgentUtils.prepareMergeInput(illnessPart, otherPart));
                        String finalPrompt = buildFinalMergePrompt(hasFirstIllnessCourse);
                        return callWithPrompt(finalPrompt, finalInput);
                    }
            );
            String finalOutput = finalOutputFuture.join();

            /*String illnessEventPart = agentUtils.formatSectionWithSplit(
                    "pat_illnessCourse",
                    illnessJson,
                    chunk -> callWithPrompt(FormatAgentPrompt.ILLNESS_COURSE_PROMPT, chunk)
            );
            String otherEventPart = agentUtils.formatSectionWithSplit(
                    "otherInfo",
                    otherJson,
                    chunk -> callWithPrompt(FormatAgentPrompt.TIME_EVENT_PROMPT, chunk)
            );

            String finalEventInput = agentUtils.toJson(agentUtils.prepareMergeInput(illnessEventPart, otherEventPart));
            String finalEventOutput = callWithPrompt(FormatAgentPrompt.TIME_MERGE_EVENT_PROMPT, finalEventInput);*/

            PatientContext context = new PatientContext();
            context.setSource("format-agent");
            context.setCreatedAt(LocalDateTime.now());
            context.setContextJson(AgentUtils.normalizeToJson(finalOutput));
//            context.setEventJson(normalizeToJson(finalEventOutput));
            return context;
        } catch (Exception e) {
            log.error("format-agent 处理失败，回退错误消息", e);
            PatientContext context = new PatientContext();
            context.setSource("format-agent");
            context.setCreatedAt(LocalDateTime.now());
            context.setContextJson("{\"code\":\"500\",\"message\":\"格式化失败，llm未处理\"}");
            return context;
        }
    }

    private String formatIllnessSection(String illnessJson) {
        return AgentUtils.formatSectionWithSplit(
                "pat_illnessCourse",
                illnessJson,
                chunk -> callWithPrompt(FormatAgentPrompt.ILLNESS_COURSE_PROMPT, chunk)
        );
    }

    private String formatOtherSection(String otherJson) {
        return AgentUtils.formatSectionWithSplit(
                "otherInfo",
                otherJson,
                chunk -> callWithPrompt(FormatAgentPrompt.OTHER_INFO_PROMPT, chunk)
        );
    }

    private String buildFinalMergePrompt(boolean hasFirstIllnessCourse) {
        String conditionOverview = "";
        String conditionOverviewDesc = "";
        if (hasFirstIllnessCourse) {
            conditionOverview = "condition_overview: [],";
            conditionOverviewDesc = """
            condition_overview
            患者主要诊断""";
        }
        String formatAgentPrompt = FormatAgentPrompt.FINAL_MERGE_PROMPT.replace("{conditionOverview}", conditionOverview);
        formatAgentPrompt = formatAgentPrompt.replace("{conditionOverviewDesc}", conditionOverviewDesc);
        return formatAgentPrompt;
    }


    /*public PatientContext formatCopy(String rawDataJson) {
        String input = StringUtils.hasText(rawDataJson) ? rawDataJson : "{}";
        try {
            JsonNode root = objectMapper.readTree(input);

            JsonNode labResultsNode = root.path("lab_results");
            JsonNode doctorOrdersNode = root.path("doctor_orders");
            JsonNode illnessCourseNode = root.path("pat_illnessCourse");
            ObjectNode otherInfoNode = objectMapper.createObjectNode();
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                if (!"lab_results".equals(key) && !"doctor_orders".equals(key) && !"pat_illnessCourse".equals(key)) {
                    JsonNode clinicalNotes = root.get("clinical_notes");
                    boolean hasFirstCourse = false;
                    if (clinicalNotes != null && clinicalNotes.isArray()) {
                        for (JsonNode note : clinicalNotes) {
                            if (note.asText().contains("首次病程记录")) {
                                hasFirstCourse = true;
                                break;
                            }
                        }
                    }
                    if (!hasFirstCourse && "patient_info".equals(key)) {
                        continue;
                    }
                    otherInfoNode.set(key, root.get(key));
                }
            }

            String labJson = agentUtils.toJson(labResultsNode);
            String doctorJson = agentUtils.toJson(doctorOrdersNode);
            String illnessJson = agentUtils.toJson(illnessCourseNode);
            String otherJson = agentUtils.toJson(otherInfoNode);

            String labPart = agentUtils.formatSectionWithSplit(
                    "lab_results",
                    labJson,
                    chunk -> callWithPrompt(FormatAgentPrompt.LAB_RESULTS_PROMPT, chunk)
            );
            String doctorPart = agentUtils.formatSectionWithSplit(
                    "doctor_orders",
                    doctorJson,
                    chunk -> callWithPrompt(FormatAgentPrompt.DOCTOR_ORDERS_PROMPT, chunk)
            );
            String illnessPart = agentUtils.formatSectionWithSplit(
                    "pat_illnessCourse",
                    illnessJson,
                    chunk -> callWithPrompt(FormatAgentPrompt.ILLNESS_COURSE_PROMPT, chunk)
            );
            String otherPart = agentUtils.formatSectionWithSplit(
                    "其他信息",
                    otherJson,
                    chunk -> callWithPrompt(FormatAgentPrompt.OTHER_INFO_PROMPT, chunk)
            );

            Map<String, Object> mergeInput = new LinkedHashMap<>();
            mergeInput.put("lab_results_part", agentUtils.parseToNode(labPart));
            mergeInput.put("doctor_orders_part", agentUtils.parseToNode(doctorPart));
            mergeInput.put("illness_course_part", agentUtils.parseToNode(illnessPart));
            mergeInput.put("other_info_part", agentUtils.parseToNode(otherPart));

            String finalInput = agentUtils.toJson(mergeInput);
            String finalOutput = callWithPrompt(FormatAgentPrompt.FINAL_MERGE_PROMPT, finalInput);

            PatientContext context = new PatientContext();
            context.setSource("format-agent");
            context.setCreatedAt(LocalDateTime.now());
            context.setContextJson(normalizeToJson(finalOutput));
            return context;
        } catch (Exception e) {
            log.error("format-agent 处理失败，回退错误消息", e);
            PatientContext context = new PatientContext();
            context.setSource("format-agent");
            context.setCreatedAt(LocalDateTime.now());
            context.setContextJson("{\"code\":\"500\",\"message\":\"格式化失败，llm未处理\"}");
            return context;
        }
    }*/

    private String callWithPrompt(String promptTemplate, String inputJson) {
        if (IllnessRecordType.FIRST_COURSE.matches(inputJson)) {
            promptTemplate = FormatAgentPrompt.FIRST_ILLNESS_COURSE_PROMPT;
        } else if (IllnessRecordType.CONSULTATION.matches(inputJson)) {
            promptTemplate = FormatAgentPrompt.CONSULTATION_ILLNESS_COURSE_PROMPT;
        } else if (IllnessRecordType.SURGERY.matches(inputJson)) {
            promptTemplate = FormatAgentPrompt.SURGERY_ILLNESS_COURSE_PROMPT;
        }
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(promptTemplate),
                new UserMessage(inputJson)
        ));
        ChatResponse response = super.callModelByPrompt(prompt);
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }

    public PatientRawDataEntity filterIllnessCourse(String firstIllnessCourse, PatientRawDataEntity rawData) {
        String dataJson = StringUtils.hasText(rawData.getDataJson()) ? rawData.getDataJson() : "{}";
        JsonNode root = AgentUtils.parseToNode(dataJson);
        String filterJson = null;
        List<PatientCourseData.PatIllnessCourse> illnessCourseList = objectMapper.convertValue(
                root.path("pat_illnessCourse"),
                new TypeReference<List<PatientCourseData.PatIllnessCourse>>() {
                });
        for (PatientCourseData.PatIllnessCourse illnessCourse : illnessCourseList) {
            String itemname = illnessCourse.getItemname();
            String illnesscontent = illnessCourse.getIllnesscontent();
            boolean isFirstIllnessCourse = IllnessRecordType.FIRST_COURSE.matches(itemname);
            if (isFirstIllnessCourse) {
                illnessCourse.setIllnesscontent(firstIllnessCourse);
                continue;
            }
            String filterContent = filterTextUtils.filterContent(firstIllnessCourse, illnesscontent);
            illnessCourse.setIllnesscontent(filterContent);
        }
        if (root.isObject()) {
            ((ObjectNode) root).set("pat_illnessCourse", objectMapper.valueToTree(illnessCourseList));
            rawData.setFilterDataJson(AgentUtils.toJson(root));
        } else {
            rawData.setFilterDataJson(AgentUtils.toJson(illnessCourseList));
        }
        return rawData;
    }
}
