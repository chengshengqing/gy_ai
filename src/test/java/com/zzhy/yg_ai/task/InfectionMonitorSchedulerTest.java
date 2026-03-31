package com.zzhy.yg_ai.task;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientService;
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

    private InfectionMonitorScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new InfectionMonitorScheduler(patientService, infectionPipeline, infectionDailyJobLogService);
    }

    @Test
    void enqueuePendingPatientsLogsSkipWhenNoPatientsFound() {
        when(patientService.listActiveReqnos()).thenReturn(Collections.emptyList());

        scheduler.enqueuePendingPatients();

        verify(infectionPipeline, never()).enqueueRawDataTasks(List.of());
        verify(infectionDailyJobLogService).log(
                InfectionJobStage.LOAD,
                InfectionJobStatus.SKIP,
                null,
                "采集扫描阶段无可入队患者"
        );
    }

    @Test
    void enqueuePendingPatientsLogsSuccessWhenTasksAreEnqueued() {
        List<String> reqnos = List.of("REQ-1", "REQ-2");
        when(patientService.listActiveReqnos()).thenReturn(reqnos);
        when(infectionPipeline.enqueueRawDataTasks(reqnos)).thenReturn(2);

        scheduler.enqueuePendingPatients();

        verify(infectionPipeline).enqueueRawDataTasks(reqnos);
        verify(infectionDailyJobLogService).log(
                InfectionJobStage.LOAD,
                InfectionJobStatus.SUCCESS,
                null,
                "scanPatients=2, enqueued=2"
        );
    }

    @Test
    void processPendingCollectTasksLogsSkipWhenNoPendingTaskExists() {
        when(infectionPipeline.processPendingRawDataTasks()).thenReturn(0);

        scheduler.processPendingCollectTasks();

        verify(infectionDailyJobLogService).log(
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
}
