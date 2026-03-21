package com.zzhy.yg_ai.ai.reactAgent;

import com.zzhy.yg_ai.ai.reactAgent.tools.MedicalStructValidationTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeMedicalStructRunner {

    private static final String STRUCTURED_OUTPUT_SCHEMA = """
            {
              \"courseTime\": [],
              \"symptoms\": [],
              \"signsAndExaminations\": [],
              \"doctorAssessment\": [],
              \"surgeryRecords\": [],
              \"treatmentPlan\": [],
              \"consultationOpinions\": [],
              \"unclassified\": []
            }
            """;

    private static final String EVENT_OUTPUT_SCHEMA = """
            [
              {
                \"eventType\": \"\",
                \"eventName\": \"\",
                \"eventTimeRaw\": \"\",
                \"attributes\": {},
                \"negation\": false,
                \"sourceText\": \"\"
              }
            ]
            """;

    private final AgentScopeMedicalAgentFactory agentFactory;
    private final AgentScopeMedicalStructProperties properties;
    private final MedicalStructValidationTools validationTools;

    public String classify(String illnessContent) {
        return invokeWithRetry(agentFactory.createClassificationAgent(), buildClassificationPrompt(illnessContent), true);
    }

    public String extractSymptomEvents(String text) {
        return invokeEvent(buildSymptomPrompt(text));
    }

    public String extractExamEvents(String text) {
        return invokeEvent(buildExamPrompt(text));
    }

    public String extractImagingEvents(String text) {
        return invokeEvent(buildImagingPrompt(text));
    }

    public String extractProcedureEvents(String text) {
        return invokeEvent(buildProcedurePrompt(text));
    }

    public String extractSurgeryEvents(String text) {
        return invokeEvent(buildSurgeryPrompt(text));
    }

    public String extractMedicationEvents(String text) {
        return invokeEvent(buildMedicationPrompt(text));
    }

    public String extractTransferEvents(String text) {
        return invokeEvent(buildTransferPrompt(text));
    }

    private String invokeEvent(String prompt) {
        return invokeWithRetry(agentFactory.createEventAgent(), prompt, false);
    }

    private String invokeWithRetry(ReActAgent agent, String prompt, boolean structuredValidation) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                Msg response = agent.call(Msg.builder().textContent(prompt).build()).block();
                String textContent = response == null ? null : response.getTextContent();
                if (textContent == null || textContent.isBlank()) {
                    throw new IllegalStateException("AgentScope 返回内容为空");
                }
                String validationResult = structuredValidation
                        ? validationTools.validateStructured(textContent)
                        : validationTools.validateEventJson(textContent);
                if (!"VALID".equals(validationResult)) {
                    throw new IllegalStateException(validationResult);
                }
                return textContent;
            } catch (RuntimeException ex) {
                lastException = ex;
                log.warn("AgentScope 调用失败，第 {} 次重试。原因: {}", attempt, ex.getMessage());
            }
        }
        throw new IllegalStateException("AgentScope 调用失败，超过最大重试次数", lastException);
    }

    private String buildClassificationPrompt(String illnessContent) {
        return """
                你是病程记录结构化分类助手。请严格根据原文语义，将输入文本完整拆分到固定 JSON 字段中。

                任务要求：
                1. 原文中的每一条有效信息都必须被输出，禁止遗漏、改写、概括、合并。
                2. 忽略序号（如 1.、2.、一、二）对分类的影响，只按语义分类。
                3. 每个片段只能出现一次；如果无法判断类别，放入 unclassified。
                4. courseTime 仅保留原文出现的第一个病程记录时间；没有则输出空数组。
                5. 输出必须是合法 JSON，且只能输出 JSON。

                字段定义：
                - courseTime：病程记录时间。
                - symptoms：主诉、现病史、既往史、个人史、家族史、患者状态等背景信息。
                - signsAndExaminations：查体、检验、病理、影像、术中客观所见等检查结果。
                - doctorAssessment：医生诊断、评估、印象、病情判断。
                - surgeryRecords：手术名称、麻醉、操作步骤、术中处理、出血量等操作记录。
                - treatmentPlan：医嘱、后续治疗、观察要点、护理和转归处理。
                - consultationOpinions：会诊、MDT、与家属沟通后的方案意见。
                - unclassified：无法明确归类但必须保留的原文。

                输出格式：
                %s

                待处理原文：
                %s
                """.formatted(STRUCTURED_OUTPUT_SCHEMA, illnessContent);
    }

    private String buildSymptomPrompt(String text) {
        return buildEventPrompt(
                "症状事件抽取",
                "抽取患者主观症状、异常感觉或异常功能表现，例如发热、腹痛、胸痛、乏力、呼吸困难、尿痛等。不要抽取检查结果、影像结论、手术名称、治疗行为，也不要抽取“排尿畅”“大便成形”等正常状态描述。",
                "eventType 固定为 symptom；eventName 填症状名称；attributes 可包含程度、部位、持续时间、频次、诱因等。"
                , text);
    }

    private String buildExamPrompt(String text) {
        return buildEventPrompt(
                "检查检验事件抽取",
                "抽取实验室检查、生命体征、体格检查、病理检查等客观检查事件，例如血常规、肝功能、血压、心率、病理回报。不要抽取纯诊断结论、治疗行为或手术步骤。",
                "eventType 固定为 examination；eventName 填检查名称；attributes 可包含结果值、单位、结论、部位、异常提示等。",
                text);
    }

    private String buildImagingPrompt(String text) {
        return buildEventPrompt(
                "影像事件抽取",
                "抽取 CT、MRI、X 线、超声、内镜、造影等影像或可视化检查事件，以及其直接检查发现。不要抽取基于影像做出的最终临床诊断。",
                "eventType 固定为 imaging；eventName 填影像检查名称；attributes 可包含检查部位、影像表现、异常发现、提示内容等。",
                text);
    }

    private String buildProcedurePrompt(String text) {
        return buildEventPrompt(
                "处置操作事件抽取",
                "抽取非手术类医疗处置或操作，例如穿刺、置管、导尿、胃肠减压、换药、吸氧、监护、输血、复苏等。不要抽取药物名称本身，也不要把完整手术名称归到这里。",
                "eventType 固定为 procedure；eventName 填操作名称；attributes 可包含部位、目的、次数、器械、结果等。",
                text);
    }

    private String buildSurgeryPrompt(String text) {
        return buildEventPrompt(
                "手术事件抽取",
                "抽取手术名称及关键手术操作事件，例如切除、吻合、探查、结扎、缝合、引流等。可以包含麻醉方式、术中出血量、术中发现等与手术直接相关的信息。",
                "eventType 固定为 surgery；eventName 填手术或关键术中操作名称；attributes 可包含麻醉方式、术中发现、出血量、输液量、切除范围等。",
                text);
    }

    private String buildMedicationPrompt(String text) {
        return buildEventPrompt(
                "用药事件抽取",
                "抽取药物使用、停药、调整剂量、给药方案等事件，例如抗炎、抑酸、胰岛素、化疗方案、静滴某药。不要抽取仅表示治疗原则但没有药物信息的泛化描述。",
                "eventType 固定为 medication；eventName 填药物或方案名称；attributes 可包含剂量、频次、途径、疗程、目的、调整说明等。",
                text);
    }

    private String buildTransferPrompt(String text) {
        return buildEventPrompt(
                "转运转科事件抽取",
                "抽取转科、转入转出、送 ICU、转院、护送检查等地点/科室流转事件，以及相关接收科室信息。",
                "eventType 固定为 transfer；eventName 填转运或转科动作；attributes 可包含转出科室、转入科室、原因、陪护/方式、去向等。",
                text);
    }

    private String buildEventPrompt(String taskName, String scope, String outputRule, String text) {
        return """
                你是医学事件抽取助手，请从输入文本中抽取%s。

                抽取要求：
                1. 每个事件独立输出，不能合并。
                2. 不得遗漏符合条件的事件，不得虚构文本中不存在的信息。
                3. 每个事件必须保留 sourceText，内容为支撑该事件的原文片段。
                4. 如果出现“无、未见、否认、未提示”等否定表达，negation 设为 true。
                5. 如果没有任何符合条件的事件，输出 []。
                6. 只能输出合法 JSON 数组，不要输出解释说明。

                抽取范围：
                %s

                输出规则：
                %s

                统一输出结构示例：
                %s

                待处理文本：
                %s
                """.formatted(taskName, scope, outputRule, EVENT_OUTPUT_SCHEMA, text);
    }
}
