package com.zzhy.yg_ai.task;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfectionMonitorSchedulerTest {

    @Mock
    private PatientService patientService;

    @Mock
    private InfectionPipeline infectionPipeline;

    @Mock
    private InfectionDailyJobLogService infectionDailyJobLogService;

    @Mock
    private PatientRawDataCollectTaskService patientRawDataCollectTaskService;

    private InfectionMonitorScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new InfectionMonitorScheduler(
                patientService,
                infectionPipeline,
                infectionDailyJobLogService,
                patientRawDataCollectTaskService
        );
    }

    @Test
    void enqueuePendingPatientsSkipsWhenSourceBatchHasNotAdvanced() {
        LocalDateTime batchTime = LocalDateTime.of(2026, 4, 3, 3, 0);
        when(patientService.getLatestSourceBatchTime()).thenReturn(batchTime);
        when(patientRawDataCollectTaskService.getLatestSourceLastTime()).thenReturn(batchTime);

        scheduler.enqueuePendingPatients();

        verify(patientService, never()).listActiveReqnos();
        verify(infectionPipeline, never()).enqueueRawDataTasks(List.of(), batchTime);
    }

    @Test
    void enqueuePendingPatientsLogsSuccessWhenTasksAreEnqueued() {
        LocalDateTime batchTime = LocalDateTime.of(2026, 4, 3, 6, 0);
        List<String> reqnos = List.of("REQ-1", "REQ-2");
        when(patientService.getLatestSourceBatchTime()).thenReturn(batchTime);
        when(patientRawDataCollectTaskService.getLatestSourceLastTime()).thenReturn(LocalDateTime.of(2026, 4, 3, 3, 0));
        when(patientService.listActiveReqnos()).thenReturn(reqnos);
        when(infectionPipeline.enqueueRawDataTasks(reqnos, batchTime)).thenReturn(2);

        scheduler.enqueuePendingPatients();

        verify(infectionPipeline).enqueueRawDataTasks(reqnos, batchTime);
        verify(infectionDailyJobLogService).log(
                InfectionJobStage.LOAD,
                InfectionJobStatus.SUCCESS,
                null,
                "sourceBatchTime=2026-04-03T06:00, scanPatients=2, enqueued=2"
        );
    }

    @Test
    void processPendingCollectTasksSkipsPersistentSkipLogWhenNoPendingTaskExists() {
        when(infectionPipeline.processPendingRawDataTasks()).thenReturn(0);

        scheduler.processPendingCollectTasks();

        verify(infectionDailyJobLogService, never()).log(
                InfectionJobStage.LOAD,
                InfectionJobStatus.SKIP,
                null,
                "采集执行阶段无待处理任务"
        );
    }

    @Test
    void processPendingCollectTasksLogsErrorWhenPipelineThrows() {
        when(infectionPipeline.processPendingRawDataTasks()).thenThrow(new RuntimeException("boom"));

        scheduler.processPendingCollectTasks();

        verify(infectionDailyJobLogService).log(
                InfectionJobStage.LOAD,
                InfectionJobStatus.ERROR,
                null,
                "院感采集执行任务失败: boom"
        );
    }

    @Test
    void enqueuePendingPatientsReturnsWhenNoPatientsFound() {
        LocalDateTime batchTime = LocalDateTime.of(2026, 4, 3, 9, 0);
        when(patientService.getLatestSourceBatchTime()).thenReturn(batchTime);
        when(patientRawDataCollectTaskService.getLatestSourceLastTime()).thenReturn(LocalDateTime.of(2026, 4, 3, 6, 0));
        when(patientService.listActiveReqnos()).thenReturn(Collections.emptyList());

        scheduler.enqueuePendingPatients();

        verify(infectionPipeline, never()).enqueueRawDataTasks(List.of(), batchTime);
    }
}
