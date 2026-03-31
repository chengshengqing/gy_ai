package com.zzhy.yg_ai.task;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
class StructDataFormatSchedulerTest {

    @Mock
    private InfectionPipeline infectionPipeline;

    @Mock
    private InfectionDailyJobLogService infectionDailyJobLogService;

    private StructDataFormatScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new StructDataFormatScheduler(infectionPipeline, infectionDailyJobLogService);
    }

    @Test
    void formatPendingStructDataLogsSkipWhenNoTaskExists() {
        when(infectionPipeline.processPendingStructData()).thenReturn(0);

        scheduler.formatPendingStructData();

        verify(infectionDailyJobLogService).log(
                InfectionJobStage.NORMALIZE,
                InfectionJobStatus.SKIP,
                null,
                "本轮无待处理结构化任务"
        );
    }

    @Test
    void formatPendingStructDataLogsSuccessWhenTasksProcessed() {
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
    void formatPendingStructDataLogsErrorWhenPipelineThrows() {
        when(infectionPipeline.processPendingStructData()).thenThrow(new RuntimeException("normalize failed"));

        scheduler.formatPendingStructData();

        verify(infectionDailyJobLogService).log(
                InfectionJobStage.NORMALIZE,
                InfectionJobStatus.ERROR,
                null,
                "结构化格式化定时任务执行失败: normalize failed"
        );
    }
}
