package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.common.FilterTextUtils;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.mapper.PatientRawDataMapper;
import com.zzhy.yg_ai.mapper.PatientSummaryMapper;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatientServiceImplTest {

    @Mock
    private PatientRawDataMapper patientRawDataMapper;

    @Mock
    private PatientSummaryMapper patientSummaryMapper;

    @Mock
    private FilterTextUtils filterTextUtils;

    @Mock
    private InfectionDailyJobLogService infectionDailyJobLogService;

    @Mock
    private PatientRawDataChangeTaskService patientRawDataChangeTaskService;

    private PatientServiceImpl patientService;

    @BeforeEach
    void setUp() {
        InfectionMonitorProperties infectionMonitorProperties = new InfectionMonitorProperties();
        patientService = new PatientServiceImpl(
                patientRawDataMapper,
                patientSummaryMapper,
                new ObjectMapper(),
                filterTextUtils,
                infectionMonitorProperties,
                infectionDailyJobLogService,
                patientRawDataChangeTaskService
        );
    }

    @Test
    void listActiveReqnosUsesLatestLoadSuccessTimeAndNormalizesValues() {
        LocalDateTime latestLoadSuccessTime = LocalDateTime.of(2026, 3, 29, 9, 0);
        when(infectionDailyJobLogService.getLatestSuccessTime(InfectionJobStage.LOAD)).thenReturn(latestLoadSuccessTime);
        when(patientRawDataMapper.selectActiveReqnos(latestLoadSuccessTime, 30, 200))
                .thenReturn(List.of(" REQ-1 ", "", "REQ-2", "REQ-1", "   "));

        List<String> reqnos = patientService.listActiveReqnos();

        assertEquals(List.of("REQ-1", "REQ-2"), reqnos);
        verify(patientRawDataMapper).selectActiveReqnos(latestLoadSuccessTime, 30, 200);
    }

    @Test
    void collectAndSaveRawDataResultForNewPatientInsertsRawDataAndAppendsChangeTasks() {
        String reqno = "REQ-1001";
        LocalDateTime loadSuccessTime = LocalDateTime.of(2026, 3, 29, 8, 0);
        LocalDateTime inhosday = LocalDateTime.of(2026, 3, 28, 10, 30);
        PatientCourseData.PatInfor patInfor = new PatientCourseData.PatInfor();
        patInfor.setReqno(reqno);
        patInfor.setInhosday(inhosday);
        patInfor.setDisname("肺炎");
        patInfor.setAge("65");

        when(patientRawDataMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(infectionDailyJobLogService.getLatestSuccessTime(InfectionJobStage.LOAD)).thenReturn(loadSuccessTime);
        when(patientRawDataMapper.selectPatInfor(reqno)).thenReturn(patInfor);
        when(patientRawDataMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        mockEmptyCourseQueries(reqno);
        when(patientRawDataMapper.insert(any(PatientRawDataEntity.class))).thenAnswer(invocation -> {
            PatientRawDataEntity entity = invocation.getArgument(0);
            entity.setId(101L);
            return 1;
        });

        RawDataCollectResult result = patientService.collectAndSaveRawDataResult(reqno);

        assertEquals("success", result.getStatus());
        assertEquals(1, result.getSavedDays());
        assertEquals("患者语义块采集完成", result.getMessage());

        ArgumentCaptor<PatientRawDataEntity> rawDataCaptor = ArgumentCaptor.forClass(PatientRawDataEntity.class);
        verify(patientRawDataMapper).insert(rawDataCaptor.capture());
        PatientRawDataEntity inserted = rawDataCaptor.getValue();
        assertEquals(reqno, inserted.getReqno());
        assertEquals(inhosday.toLocalDate(), inserted.getDataDate());
        assertNotNull(inserted.getLastTime());
        assertFalse(inserted.getDataJson().isBlank());
        assertFalse(inserted.getFilterDataJson().isBlank());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PatientRawDataChangeTaskEntity>> changeTasksCaptor = ArgumentCaptor.forClass(List.class);
        verify(patientRawDataChangeTaskService).appendChanges(changeTasksCaptor.capture());
        List<PatientRawDataChangeTaskEntity> changeTasks = changeTasksCaptor.getValue();
        assertEquals(1, changeTasks.size());
        PatientRawDataChangeTaskEntity changeTask = changeTasks.get(0);
        assertEquals(101L, changeTask.getPatientRawDataId());
        assertEquals(reqno, changeTask.getReqno());
        assertEquals(inhosday.toLocalDate(), changeTask.getDataDate());
        assertNotNull(changeTask.getRawDataLastTime());
    }

    private void mockEmptyCourseQueries(String reqno) {
        when(patientRawDataMapper.selectDiagByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectBodySurfaceByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectLongDoctorAdviceByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectTemporaryDoctorAdviceByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectSgDoctorAdviceByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectIllnessCourseByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectTestSamByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectUseMedicineByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectVideoResultByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectTransferByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectOpsByReqno(anyString(), any())).thenReturn(Collections.emptyList());
        when(patientRawDataMapper.selectPatTestByReqno(anyString(), any())).thenReturn(Collections.emptyList());
    }
}
