package com.zzhy.yg_ai.service.impl;

import com.zzhy.yg_ai.ai.gateway.WarningModelGateway;
import com.zzhy.yg_ai.ai.prompt.WarningPromptCatalog;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.enums.InfectionNodeType;
import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.JudgeDecisionResult;
import com.zzhy.yg_ai.service.InfectionJudgeService;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import com.zzhy.yg_ai.service.casejudge.InfectionCaseJudgeSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 第二层法官节点。
 * 当前使用单一 LLM 裁决，若输出非法则回退到确定性最小结果，避免整条 CASE_RECOMPUTE 链失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfectionJudgeServiceImpl implements InfectionJudgeService {

    private static final String MODEL_NAME = "warning-agent-chat-model";

    private final WarningModelGateway warningAgent;
    private final InfectionLlmNodeRunService infectionLlmNodeRunService;
    private final InfectionCaseJudgeSupport infectionCaseJudgeSupport;

    @Override
    public JudgeDecisionResult judge(InfectionEvidencePacket packet, LocalDateTime judgeTime) {
        InfectionEvidencePacket safePacket = packet == null ? InfectionEvidencePacket.builder().build() : packet;
        String reqno = safePacket.reqno();
        String promptVersion = WarningPromptCatalog.CASE_JUDGE_PROMPT_VERSION;
        String inputPayload = infectionCaseJudgeSupport.buildInputPayload(safePacket);
        InfectionLlmNodeRunEntity runEntity = buildPendingRun(reqno, inputPayload, promptVersion);
        infectionLlmNodeRunService.createPendingRun(runEntity);
        long startedAt = System.currentTimeMillis();
        String rawOutput = null;
        try {
            String prompt = WarningPromptCatalog.buildCaseJudgePrompt();
            rawOutput = warningAgent.callCaseJudge(prompt, inputPayload);
            JudgeDecisionResult parsed = infectionCaseJudgeSupport.parseDecision(rawOutput, safePacket, judgeTime);
            infectionLlmNodeRunService.markSuccess(
                    runEntity.getId(),
                    rawOutput,
                    infectionCaseJudgeSupport.buildRunPayload(safePacket, parsed, false, null),
                    infectionCaseJudgeSupport.extractConfidence(rawOutput),
                    System.currentTimeMillis() - startedAt
            );
            return parsed;
        } catch (Exception e) {
            JudgeDecisionResult fallback = infectionCaseJudgeSupport.buildFallbackDecision(safePacket, judgeTime);
            try {
                infectionLlmNodeRunService.markFailed(
                        runEntity.getId(),
                        rawOutput,
                        infectionCaseJudgeSupport.buildRunPayload(safePacket, fallback, true, e.getMessage()),
                        "CASE_JUDGE_FAILED",
                        e.getMessage(),
                        System.currentTimeMillis() - startedAt
                );
            } catch (Exception markFailedError) {
                e.addSuppressed(markFailedError);
                log.error("Failed to persist case judge failure audit, reqno={}, runId={}",
                        reqno, runEntity.getId(), markFailedError);
            }
            log.warn("院感法官节点调用失败，使用 fallback，reqno={}", reqno, e);
            return fallback;
        }
    }

    private InfectionLlmNodeRunEntity buildPendingRun(String reqno, String inputPayload, String promptVersion) {
        InfectionLlmNodeRunEntity entity = new InfectionLlmNodeRunEntity();
        entity.setReqno(reqno);
        entity.setNodeRunKey(UUID.randomUUID().toString());
        entity.setNodeType(InfectionNodeType.CASE_JUDGE.name());
        entity.setNodeName("infection-case-judge");
        entity.setPromptVersion(promptVersion);
        entity.setModelName(MODEL_NAME);
        entity.setInputPayload(inputPayload);
        return entity;
    }
}
