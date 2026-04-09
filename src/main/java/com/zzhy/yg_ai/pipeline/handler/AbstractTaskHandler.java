package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorExecutionContextHolder;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorTaskSummaryResolver;

public abstract class AbstractTaskHandler<T, R> {

    public final R handle(T task) {
        PipelineMonitorExecutionContextHolder.updateReqnoHint(PipelineMonitorTaskSummaryResolver.resolveReqnoHint(task));
        try {
            R result = process(task);
            afterHandle(result);
            PipelineMonitorExecutionContextHolder.updateTaskSummary(
                    PipelineMonitorTaskSummaryResolver.resolveTaskSummary(result)
            );
            return result;
        } catch (RuntimeException | Error e) {
            PipelineMonitorExecutionContextHolder.updateTaskSummary(
                    PipelineMonitorTaskSummaryResolver.failedSummary(
                            PipelineMonitorExecutionContextHolder.currentReqnoHint()
                    )
            );
            throw e;
        }
    }

    protected abstract R process(T task);

    protected void afterHandle(R result) {
    }
}
