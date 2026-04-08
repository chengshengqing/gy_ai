package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zzhy.yg_ai.ai.agent.WarningAgent;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.InfectionJudgePrecompute;
import com.zzhy.yg_ai.domain.model.JudgeCatalogEvent;
import com.zzhy.yg_ai.domain.model.JudgeDecisionBuckets;
import com.zzhy.yg_ai.domain.model.JudgeDecisionResult;
import com.zzhy.yg_ai.domain.model.JudgeEvidenceGroup;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfectionJudgeServiceImplTest {

    @Mock
    private WarningAgent warningAgent;
    @Mock
    private InfectionLlmNodeRunService infectionLlmNodeRunService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private InfectionJudgeServiceImpl infectionJudgeService;

    @BeforeEach
    void setUp() {
        infectionJudgeService = new InfectionJudgeServiceImpl(warningAgent, infectionLlmNodeRunService, objectMapper);
    }

    @Test
    void judgeWritesNormalizedOutputStats() throws Exception {
        when(infectionLlmNodeRunService.createPendingRun(any())).thenAnswer(invocation -> {
            InfectionLlmNodeRunEntity entity = invocation.getArgument(0);
            entity.setId(1001L);
            return entity;
        });
        when(warningAgent.callCaseJudge(any(), any())).thenReturn("""
                {
                  "infectionPolarity": "support",
                  "decisionStatus": "candidate",
                  "warningLevel": "medium",
                  "primarySite": "respiratory",
                  "nosocomialLikelihood": "medium",
                  "decisionReason": "当前存在支持性证据，需继续观察。",
                  "newSupportingKeys": ["k-1", "bad-key"],
                  "newAgainstKeys": ["k-2"],
                  "newRiskKeys": ["missing-key"],
                  "dismissedKeys": ["unknown-key"],
                  "requiresFollowUp": true,
                  "resultVersion": 3,
                  "confidence": 0.74
                }
                """);

        InfectionEvidencePacket packet = InfectionEvidencePacket.builder()
                .reqno("REQ-1")
                .snapshotVersion(2)
                .eventCatalog(List.of(
                        JudgeCatalogEvent.builder().eventId("A1").eventKey("k-1").build(),
                        JudgeCatalogEvent.builder().eventId("A2").eventKey("k-2").build()
                ))
                .evidenceGroups(List.of(
                        JudgeEvidenceGroup.builder().groupId("G1").representativeEventKey("k-1").build(),
                        JudgeEvidenceGroup.builder().groupId("G2").representativeEventKey("k-2").build()
                ))
                .decisionBuckets(JudgeDecisionBuckets.builder()
                        .newGroupIds(List.of("G1"))
                        .supportGroupIds(List.of("G1"))
                        .againstGroupIds(List.of("G2"))
                        .riskGroupIds(List.of())
                        .build())
                .precomputed(InfectionJudgePrecompute.builder()
                        .newOnsetFlag(true)
                        .after48hFlag("unknown")
                        .procedureRelatedFlag(false)
                        .deviceRelatedFlag(false)
                        .build())
                .build();

        JudgeDecisionResult result = infectionJudgeService.judge(packet, LocalDateTime.of(2026, 4, 7, 10, 0));

        assertEquals(List.of("k-1"), result.newSupportingKeys());
        assertEquals(List.of("k-2"), result.newAgainstKeys());
        assertEquals(List.of(), result.newRiskKeys());
        assertEquals(List.of(), result.dismissedKeys());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(infectionLlmNodeRunService).markSuccess(any(), any(), payloadCaptor.capture(), any(), any());
        JsonNode root = objectMapper.readTree(payloadCaptor.getValue());
        JsonNode stats = root.path("stats");
        assertEquals(2, stats.path("raw_group_count").asInt());
        assertEquals(1, stats.path("support_group_count").asInt());
        assertEquals(1, stats.path("against_group_count").asInt());
        assertEquals(0, stats.path("risk_group_count").asInt());
        assertEquals(1, stats.path("new_group_count").asInt());
        assertEquals(2, stats.path("referenced_key_count").asInt());
        assertEquals(1, stats.path("selected_supporting_key_count").asInt());
        assertEquals(1, stats.path("selected_against_key_count").asInt());
        assertEquals(0, stats.path("selected_risk_key_count").asInt());
        assertEquals(0, stats.path("dismissed_key_count").asInt());
        assertEquals(false, stats.path("fallback_used").asBoolean());
    }
}
