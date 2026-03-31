package com.zzhy.yg_ai.ai.prompt;

import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import org.springframework.stereotype.Component;

@Component
public class WarningAgentPrompt {

    public static final String EVENT_EXTRACTOR_PROMPT_VERSION = "infection-event-extractor-v1";

    private static final String COMMON_RULES = """
            你是院感预警事件抽取器。
            任务：从输入的单个 EvidenceBlock 中提取可入库的院感相关事件。

            输出必须严格为 JSON，不允许输出解释文字。
            输出格式固定：
            {
              "status": "success|skipped",
              "confidence": 0.0,
              "events": [
                {
                  "event_time": "yyyy-MM-dd HH:mm:ss.SSS",
                  "event_type": "diagnosis|vital_sign|lab_panel|lab_result|microbiology|imaging|order|device|procedure|note|assessment|consult|problem",
                  "event_subtype": "fever|lab_abnormal|culture_ordered|culture_positive|antibiotic_started|antibiotic_upgraded|procedure_exposure|device_exposure|imaging_infection_hint|infection_positive_statement|infection_negative_statement|contamination_statement|contamination_possible|colonization_statement|colonization_possible",
                  "body_site": "urinary|respiratory|upper_respiratory|lower_respiratory|pleural|cardiac_valve|myocardial_pericardial|mediastinum|vascular|bloodstream|blood|gastrointestinal|abdominal|intra_abdominal|central_nervous_system|surgical_site|superficial_incision|deep_incision|organ_space|skin_soft_tissue|burn|joint|bone_joint|genital|eye_ear_oral|systemic|unknown|other",
                  "event_name": "简短事件名",
                  "event_value": null,
                  "event_unit": null,
                  "abnormal_flag": null,
                  "infection_related": true,
                  "negation_flag": false,
                  "uncertainty_flag": false,
                  "clinical_meaning": "infection_support|infection_against|infection_uncertain|device_exposure|procedure_exposure|screening|baseline_problem",
                  "source_text": "支持该事件的原文"
                }
              ]
            }

            护栏：
            1. 只基于当前 block 和提供的 timeline_context 输出。
            2. 没有明确院感相关事件时返回 {"status":"skipped","confidence":0.2,"events":[]}
            3. 不允许杜撰时间、部位、病原体。
            4. 结构化事实优先，临床文本和中间语义可补充否定、怀疑、污染、定植、风险判断。
            5. source_text 必须来自输入内容，不得凭空改写医学结论。
            """;

    private static final String STRUCTURED_FACT_RULES = """
            当前 block_type=STRUCTURED_FACT。
            重点抽取：
            - 发热等感染相关生命体征
            - WBC/CRP/PCT 等感染相关检验异常
            - 培养送检/培养阳性
            - 抗菌药新开或升级
            - 手术、导尿、置管、插管等暴露
            - 影像提示感染灶

            不要把普通背景数据全部转成事件，只输出与院感判断相关的关键事实。
            """;

    private static final String CLINICAL_TEXT_RULES = """
            当前 block_type=CLINICAL_TEXT。
            重点抽取：
            - 支持感染、排除感染、待排感染
            - 污染判断、定植判断
            - 与感染相关的会诊建议、病程评估
            - 文本中明确出现的新手术/器械暴露信息

            必须正确处理否定词、推测词、排除词。
            """;

    private static final String MID_SEMANTIC_RULES = """
            当前 block_type=MID_SEMANTIC。
            重点抽取：
            - core_problems 中的感染问题
            - differential_diagnosis 中的待排感染
            - risk/risk_alerts/risk_candidates 中的器械暴露、操作暴露、感染风险

            对纯风险项，可使用 event_type=problem 或 assessment，并将 clinical_meaning 设为 device_exposure / procedure_exposure / infection_uncertain。
            """;

    public static String buildEventExtractorPrompt(EvidenceBlockType blockType) {
        if (blockType == null) {
            return COMMON_RULES;
        }
        return switch (blockType) {
            case STRUCTURED_FACT -> COMMON_RULES + "\n" + STRUCTURED_FACT_RULES;
            case CLINICAL_TEXT -> COMMON_RULES + "\n" + CLINICAL_TEXT_RULES;
            case MID_SEMANTIC -> COMMON_RULES + "\n" + MID_SEMANTIC_RULES;
            case TIMELINE_CONTEXT -> COMMON_RULES + "\n当前 block_type=TIMELINE_CONTEXT，只可作背景参考，不应单独抽取事件。";
        };
    }

}
