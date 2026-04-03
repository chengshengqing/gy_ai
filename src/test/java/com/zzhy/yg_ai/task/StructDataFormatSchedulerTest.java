package com.zzhy.yg_ai.task;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StructDataFormatSchedulerTest {

    @Mock
    private InfectionPipeline infectionPipeline;

    @Mock
    private InfectionDailyJobLogService infectionDailyJobLogService;

    @Mock
    private PatientService patientService;

    @Mock
    private PatientRawDataChangeTaskService patientRawDataChangeTaskService;

    private StructDataFormatScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new StructDataFormatScheduler(
                infectionPipeline,
                infectionDailyJobLogService,
                patientService,
                patientRawDataChangeTaskService
        );
    }

    @Test
    void formatPendingStructDataDoesNotPersistSkipLogWhenNoTaskExists() {
        when(patientService.listActiveReqnos()).thenReturn(List.of());
        when(infectionPipeline.processPendingStructData()).thenReturn(0);

        scheduler.formatPendingStructData();

        verify(infectionDailyJobLogService, never()).log(
                InfectionJobStage.NORMALIZE,
                InfectionJobStatus.SKIP,
                null,
                "本轮无待处理结构化任务"
        );
    }

    @Test
    void formatPendingStructDataLogsSuccessWhenTasksProcessed() {
        when(patientService.listActiveReqnos()).thenReturn(List.of());
        when(infectionPipeline.processPendingStructData()).thenReturn(3);

        scheduler.formatPendingStructData();

        verify(infectionDailyJobLogService).log(
                InfectionJobStage.NORMALIZE,
                InfectionJobStatus.SUCCESS,
                null,
                "formattedCount=3"
        );
    }

    @Test
    void formatPendingStructDataRepairsMissingTasksWithinRecent14Days() {
        when(patientService.listActiveReqnos()).thenReturn(List.of("REQ-1001", "REQ-1002"));
        when(infectionPipeline.processPendingStructData()).thenReturn(0);

        LocalDateTime before = LocalDateTime.now();
        scheduler.formatPendingStructData();
        LocalDateTime after = LocalDateTime.now();

        verify(patientRawDataChangeTaskService).repairMissingStructTasks(
                eq(List.of("REQ-1001", "REQ-1002")),
                argThat(lastTimeFrom -> {
                    LocalDateTime lowerBound = before.minusDays(14);
                    LocalDateTime upperBound = after.minusDays(14);
                    long lowerDelta = Math.abs(Duration.between(lowerBound, lastTimeFrom).toSeconds());
                    long upperDelta = Math.abs(Duration.between(upperBound, lastTimeFrom).toSeconds());
                    return lowerDelta <= 2 || upperDelta <= 2;
                }),
                eq(20)
        );
    }
}
