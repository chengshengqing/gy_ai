package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.mapper.PatientRawDataChangeTaskMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PatientRawDataChangeTaskServiceImplTest {

    @Mock
    private PatientRawDataChangeTaskMapper patientRawDataChangeTaskMapper;

    private PatientRawDataChangeTaskServiceImpl patientRawDataChangeTaskService;

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(PatientRawDataChangeTaskEntity.class) == null) {
            MapperBuilderAssistant assistant =
                    new MapperBuilderAssistant(new MybatisConfiguration(), "testMapper");
            TableInfoHelper.initTableInfo(assistant, PatientRawDataChangeTaskEntity.class);
        }
        StructDataFormatProperties properties = new StructDataFormatProperties();
        properties.setMaxAttempts(7);
        patientRawDataChangeTaskService = new PatientRawDataChangeTaskServiceImpl(properties);
        ReflectionTestUtils.setField(patientRawDataChangeTaskService, "baseMapper", patientRawDataChangeTaskMapper);
    }

    @Test
    void appendChangesInitializesTaskExecutionFieldsBeforeInsert() {
        PatientRawDataChangeTaskEntity task = new PatientRawDataChangeTaskEntity();
        task.setPatientRawDataId(10L);
        task.setReqno("REQ-2001");
        task.setDataDate(LocalDate.of(2026, 3, 29));
        task.setRawDataLastTime(LocalDateTime.of(2026, 3, 29, 10, 0));

        patientRawDataChangeTaskService.appendChanges(List.of(task));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PatientRawDataChangeTaskEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(patientRawDataChangeTaskMapper).insertBatchWithoutId(captor.capture());
        PatientRawDataChangeTaskEntity inserted = captor.getValue().get(0);
        assertEquals("STRUCT_PENDING", inserted.getStatus());
        assertEquals(0, inserted.getAttemptCount());
        assertEquals(7, inserted.getMaxAttempts());
        assertNotNull(inserted.getAvailableAt());
        assertNotNull(inserted.getCreateTime());
        assertNotNull(inserted.getUpdateTime());
    }

    @Test
    void claimPendingStructTasksReturnsClaimedRowsByTaskIds() {
        PatientRawDataChangeTaskServiceImpl serviceSpy = spy(patientRawDataChangeTaskService);
        when(patientRawDataChangeTaskMapper.selectPendingReqnos(anyList(), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of("REQ-2001", "REQ-2002"));

        PatientRawDataChangeTaskEntity pendingTask1 = new PatientRawDataChangeTaskEntity();
        pendingTask1.setId(1L);
        pendingTask1.setReqno("REQ-2001");
        pendingTask1.setStatus("STRUCT_PENDING");

        PatientRawDataChangeTaskEntity pendingTask2 = new PatientRawDataChangeTaskEntity();
        pendingTask2.setId(2L);
        pendingTask2.setReqno("REQ-2002");
        pendingTask2.setStatus("STRUCT_PENDING");

        PatientRawDataChangeTaskEntity runningTask1 = new PatientRawDataChangeTaskEntity();
        runningTask1.setId(1L);
        runningTask1.setReqno("REQ-2001");
        runningTask1.setStatus("STRUCT_RUNNING");

        PatientRawDataChangeTaskEntity runningTask2 = new PatientRawDataChangeTaskEntity();
        runningTask2.setId(2L);
        runningTask2.setReqno("REQ-2002");
        runningTask2.setStatus("STRUCT_RUNNING");

        doReturn(List.of(pendingTask1, pendingTask2), List.of(runningTask1, runningTask2))
                .when(serviceSpy)
                .list(any(LambdaQueryWrapper.class));
        doReturn(true).when(serviceSpy).update(any(LambdaUpdateWrapper.class));

        List<PatientRawDataChangeTaskEntity> claimed = serviceSpy.claimPendingStructTasks(1);

        assertEquals(2, claimed.size());
        assertEquals(1L, claimed.get(0).getId());
        assertEquals(2L, claimed.get(1).getId());
        verify(serviceSpy, times(2)).list(any(LambdaQueryWrapper.class));
        verify(serviceSpy).update(any(LambdaUpdateWrapper.class));
    }
}
