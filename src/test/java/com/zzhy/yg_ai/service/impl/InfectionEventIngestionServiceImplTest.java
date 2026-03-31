package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import com.zzhy.yg_ai.service.EventNormalizerService;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfectionEventIngestionServiceImplTest {

    @Mock
    private EventNormalizerService eventNormalizerService;

    @Mock
    private InfectionEventPoolService infectionEventPoolService;

    private InfectionEventIngestionServiceImpl infectionEventIngestionService;

    @BeforeEach
    void setUp() {
        infectionEventIngestionService = new InfectionEventIngestionServiceImpl(eventNormalizerService, infectionEventPoolService);
    }

    @Test
    void normalizeAndSaveDelegatesToNormalizerAndEventPool() {
        EvidenceBlock block = new EvidenceBlock(
                "block-9",
                "REQ-9010",
                400L,
                LocalDate.of(2026, 3, 30),
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                "pat_illnessCourse.N-3",
                "病程记录",
                "{}",
                false
        );

        NormalizedInfectionEvent normalized = new NormalizedInfectionEvent();
        normalized.setReqno("REQ-9010");

        InfectionEventPoolEntity saved = new InfectionEventPoolEntity();
        saved.setReqno("REQ-9010");

        when(eventNormalizerService.normalize(block, "{\"events\":[]}", InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                "v1", "gpt-test", new BigDecimal("0.88"))).thenReturn(List.of(normalized));
        when(infectionEventPoolService.saveNormalizedEvents(anyList())).thenReturn(List.of(saved));

        List<InfectionEventPoolEntity> result = infectionEventIngestionService.normalizeAndSave(
                block,
                "{\"events\":[]}",
                InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                "v1",
                "gpt-test",
                new BigDecimal("0.88")
        );

        assertEquals(1, result.size());
        assertEquals("REQ-9010", result.get(0).getReqno());
        verify(eventNormalizerService).normalize(block, "{\"events\":[]}", InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                "v1", "gpt-test", new BigDecimal("0.88"));
        verify(infectionEventPoolService).saveNormalizedEvents(List.of(normalized));
    }
}
