package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventStatus;
import com.zzhy.yg_ai.domain.enums.InfectionEventType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.mapper.InfectionEventPoolMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InfectionEventPoolServiceImplTest {

    @Mock
    private InfectionEventPoolMapper infectionEventPoolMapper;

    private InfectionEventPoolServiceImpl infectionEventPoolService;

    @BeforeEach
    void setUp() {
        infectionEventPoolService = new InfectionEventPoolServiceImpl();
        ReflectionTestUtils.setField(infectionEventPoolService, "baseMapper", infectionEventPoolMapper);
    }

    @Test
    void createEventInitializesDefaultFields() {
        InfectionEventPoolEntity entity = new InfectionEventPoolEntity();
        entity.setReqno("REQ-8001");
        entity.setRawDataId(12L);
        entity.setDataDate(LocalDate.of(2026, 3, 30));
        entity.setSourceType(InfectionSourceType.RAW.code());
        entity.setEventKey("REQ-8001|2026-03-30|raw|lab|wbc");
        entity.setEventType(InfectionEventType.LAB_RESULT.code());

        infectionEventPoolService.createEvent(entity);

        ArgumentCaptor<InfectionEventPoolEntity> captor = ArgumentCaptor.forClass(InfectionEventPoolEntity.class);
        verify(infectionEventPoolMapper).insert(captor.capture());
        InfectionEventPoolEntity inserted = captor.getValue();
        assertEquals(InfectionEventStatus.ACTIVE.code(), inserted.getStatus());
        assertEquals(Boolean.TRUE, inserted.getIsActive());
        assertEquals(Boolean.FALSE, inserted.getIsHardFact());
        assertNotNull(inserted.getDetectedTime());
        assertNotNull(inserted.getIngestTime());
        assertNotNull(inserted.getCreatedAt());
        assertNotNull(inserted.getUpdatedAt());
    }

    @Test
    void saveOrUpdateByEventKeyUpdatesExistingRow() {
        InfectionEventPoolEntity existing = new InfectionEventPoolEntity();
        existing.setId(99L);
        existing.setEventKey("REQ-8002|2026-03-30|raw|note|n1");

        when(infectionEventPoolMapper.selectOne(any())).thenReturn(existing);

        InfectionEventPoolEntity entity = new InfectionEventPoolEntity();
        entity.setReqno("REQ-8002");
        entity.setSourceType(InfectionSourceType.RAW.code());
        entity.setEventKey(existing.getEventKey());
        entity.setEventType(InfectionEventType.ASSESSMENT.code());
        entity.setEventSubtype("infection_positive_statement");
        entity.setIsActive(Boolean.TRUE);

        infectionEventPoolService.saveOrUpdateByEventKey(entity);

        ArgumentCaptor<InfectionEventPoolEntity> captor = ArgumentCaptor.forClass(InfectionEventPoolEntity.class);
        verify(infectionEventPoolMapper).updateById(captor.capture());
        InfectionEventPoolEntity updated = captor.getValue();
        assertEquals(99L, updated.getId());
        assertEquals(InfectionEventStatus.ACTIVE.code(), updated.getStatus());
        assertNotNull(updated.getUpdatedAt());
    }
}
