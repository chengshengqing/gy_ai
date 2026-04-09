package com.zzhy.yg_ai.ai.agent;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzhy.yg_ai.ai.validator.SurveillanceResponseValidator;
import com.zzhy.yg_ai.ai.validator.SurveillanceValidationEnums;
import com.zzhy.yg_ai.common.JsonUtil;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.dto.PatillnessCourseInfo;
import com.zzhy.yg_ai.ai.validator.SurveillanceResponseValidator.ValidationResult;
import com.zzhy.yg_ai.domain.entity.AiProcessLog;
import com.zzhy.yg_ai.domain.entity.ItemsInforZhq;
import com.zzhy.yg_ai.ai.prompt.PromptTemplateManager;
import com.zzhy.yg_ai.service.AiProcessLogService;
import com.zzhy.yg_ai.service.IPatillnessCourseInfoService;
import com.zzhy.yg_ai.service.IItemsInforZhqService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SurveillanceAgent implements Agent<String, String> {

    private final ChatModel chatModel;

    private final IPatillnessCourseInfoService patillnessCourseInfoService;

    private final AiProcessLogService aiProcessLogService;

    private final IItemsInforZhqService itemsInforZhqService;

    @Override
    public String run() {
        return "";
    }

    /**
     * 调用大模型进行症候群监测分析
     *
     * @param content 动态拼接的完整内容（含诊断信息、病程内容、病程诊断）
     */
    public String callModel(String content) {
        SystemMessage systemMessage = new SystemMessage(PromptTemplateManager.buildSurveillancePrompt());
        UserMessage userMessage = new UserMessage(content);

        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));
        ChatResponse chatResponse = chatModel.call(prompt);
        return chatResponse.getResult().getOutput().getText();
    }


    public void handerSurveillance() {
        /*查询患者数据*/
        List<PatillnessCourseInfo> patillnessCourseInfos = patillnessCourseInfoService.selectList(Page.of(1, 10));
        patillnessCourseInfos.forEach(patillnessCourseInfo -> {
            String surveillanceContent = patillnessCourseInfo.buildSurveillanceContent();
            if (StringUtils.isBlank(surveillanceContent)) return;

            String content = null;
            String lastValidationFailure = null;
            for (int i = 0; i < 3; i++) {
                /*暂时截取，未来优化*/
                content = callModel(surveillanceContent.length() > 4000 ? surveillanceContent.substring(0, 4000) : surveillanceContent);
                ValidationResult result = SurveillanceResponseValidator.validate(content);

                if (result.isPassed()) {
                    lastValidationFailure = null;

                    AiProcessLog aiProcessLog = new AiProcessLog();
                    aiProcessLog.setReqno(patillnessCourseInfo.getReqno());
                    aiProcessLog.setPathosid(patillnessCourseInfo.getPathosid());
                    aiProcessLog.setProcessType("SURVEILLANCE");
                    aiProcessLog.setAiResponse(content);
                    aiProcessLog.setStatus("SUCCESS");
                    aiProcessLog.init();

                    aiProcessLogService.save(aiProcessLog);
                    break;
                }
                lastValidationFailure = result.getFailureReasonMessage();
            }
            if (lastValidationFailure != null) {
                aiProcessLogService.logFailed(
                        patillnessCourseInfo.getReqno(),
                        patillnessCourseInfo.getPathosid(),
                        null,
                        "SURVEILLANCE",
                        content,
                        "VALIDATION_FAILED",
                        lastValidationFailure,
                        "症候群监测校验失败："
                );
//                throw new RuntimeException("症候群监测校验失败：" + lastValidationFailure);
            }
            try {
                @SuppressWarnings("unused")
                JsonNode root = JsonUtil.toJsonNode(content);

                JsonNode syndromeTypeNode = root.get(SurveillanceValidationEnums.SYNDROME_TYPE);
                String syndromeTypeValue = syndromeTypeNode != null && syndromeTypeNode.isTextual() ? syndromeTypeNode.asText() : "";
                String riskLevel = root.has(SurveillanceValidationEnums.RISK_LEVEL) && root.get(SurveillanceValidationEnums.RISK_LEVEL).isTextual()
                        ? root.get(SurveillanceValidationEnums.RISK_LEVEL).asText() : "";
                if ("无".equals(syndromeTypeValue)) {
                    return;
                }
                if (SurveillanceValidationEnums.RiskLevel.NO_RISK.getValue().equals(riskLevel)
                        || SurveillanceValidationEnums.RiskLevel.LOW_RISK.getValue().equals(riskLevel)) {
                    return;
                }

                String analysisReasoning = root.has(SurveillanceValidationEnums.ANALYSIS_REASONING) && root.get(SurveillanceValidationEnums.ANALYSIS_REASONING).isTextual()
                        ? root.get(SurveillanceValidationEnums.ANALYSIS_REASONING).asText() : "";

                String keyEvidence = root.has(SurveillanceValidationEnums.KEY_EVIDENCE) && root.get(SurveillanceValidationEnums.KEY_EVIDENCE).isTextual()
                        ? root.get(SurveillanceValidationEnums.KEY_EVIDENCE).asText() : "";
                String recommendedActions = root.has(SurveillanceValidationEnums.RECOMMENDED_ACTIONS) && root.get(SurveillanceValidationEnums.RECOMMENDED_ACTIONS).isTextual()
                        ? root.get(SurveillanceValidationEnums.RECOMMENDED_ACTIONS).asText() : "";

                ItemsInforZhq itemsInforZhq = new ItemsInforZhq();
                itemsInforZhq.setReqno(patillnessCourseInfo.getReqno());
                itemsInforZhq.setPatno(patillnessCourseInfo.getPatno());
                itemsInforZhq.setPattype(patillnessCourseInfo.getPattype());
                itemsInforZhq.setPatname(patillnessCourseInfo.getPatname());
                itemsInforZhq.setSexname(patillnessCourseInfo.getSex());
                itemsInforZhq.setDictname(patillnessCourseInfo.getDisplayname());
                itemsInforZhq.setInHosdate(patillnessCourseInfo.getInhosdate());
                itemsInforZhq.setTestResult("1");
                itemsInforZhq.setSamReqno("1");
//                itemsInforZhq.setItemname("诊：" + syndromeTypeNode.asText() + ":" + analysisReasoning);
//                itemsInforZhq.setTargetSetName("诊：" + syndromeTypeNode.asText() + ":" + analysisReasoning);
//                itemsInforZhq.setDataname("诊：" + syndromeTypeNode.asText() + ":" + analysisReasoning);
                itemsInforZhq.setItemname("诊：" + syndromeTypeNode.asText());
                itemsInforZhq.setTargetSetName("诊：" + syndromeTypeNode.asText());
                itemsInforZhq.setDataname("诊：" + syndromeTypeNode.asText());
                itemsInforZhq.setTtime(DateTimeUtils.today().atStartOfDay());
                itemsInforZhq.setExedate(DateTimeUtils.now());
                itemsInforZhq.setRiskLevel(riskLevel);
                itemsInforZhq.setSyndromeType(syndromeTypeValue);
                itemsInforZhq.setKeyEvidence(keyEvidence);
                itemsInforZhq.setAnalysisReasoning(analysisReasoning);
                itemsInforZhq.setRecommendedActions(recommendedActions);
                itemsInforZhqService.save(itemsInforZhq);
                // TODO: 后续业务处理
            } catch (JsonUtil.JsonException e) {
                aiProcessLogService.logFailed(
                        patillnessCourseInfo.getReqno(),
                        patillnessCourseInfo.getPathosid(),
                        null,
                        "SURVEILLANCE",
                        content,
                        "JSON_PARSE_ERROR",
                        e.getMessage(),
                        "症候群监测校验失败："
                );
//                throw new RuntimeException(e);
            }
        });
    }
}
