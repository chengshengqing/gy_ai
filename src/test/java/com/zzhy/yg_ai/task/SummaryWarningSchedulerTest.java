package com.zzhy.yg_ai.task;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zzhy.yg_ai.ai.orchestrator.InfectionPipeline;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SummaryWarningSchedulerTest {

    @Mock
    private InfectionPipeline infectionPipeline;

    @Mock
    private InfectionDailyJobLogService infectionDailyJobLogService;

    private SummaryWarningScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SummaryWarningScheduler(infectionPipeline, infectionDailyJobLogService);
    }

    @Test
    void processPendingEventTasksDoesNotPersistSkipLogWhenNoTaskExists() {
        when(infectionPipeline.processPendingEventData()).thenReturn(0);

        scheduler.processPendingEventTasks();

        verify(infectionDailyJobLogService, never()).log(
                InfectionJobStage.LLM,
                InfectionJobStatus.SKIP,
                null,
                "本轮无待处理事件任务"
        );
    }

    @Test
    void processPendingEventTasksLogsSuccessWhenTasksProcessed() {
        when(infectionPipeline.processPendingEventData()).thenReturn(2);

        scheduler.processPendingEventTasks();

        verify(infectionDailyJobLogService).log(
                InfectionJobStage.LLM,
                InfectionJobStatus.SUCCESS,
                null,
                "extractedCount=2"
        );
    }

    @Test
    void processPendingCaseTasksLogsSuccessWhenTasksProcessed() {
        when(infectionPipeline.processPendingCaseData()).thenReturn(1);

        scheduler.processPendingCaseTasks();

        verify(infectionDailyJobLogService).log(
                InfectionJobStage.FINALIZE,
                InfectionJobStatus.SUCCESS,
                null,
                "processedCount=1"
        );
    }
}
