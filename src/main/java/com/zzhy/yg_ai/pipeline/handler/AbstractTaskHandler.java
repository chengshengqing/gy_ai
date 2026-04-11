package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorExecutionContextHolder;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorTaskSummaryResolver;
import java.util.List;
import java.util.StringJoiner;

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

    protected final Long firstTaskId(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return null;
        }
        return taskIds.get(0);
    }

    protected final String buildSummary(Object... keyValues) {
        StringJoiner joiner = new StringJoiner(", ");
        if (keyValues == null || keyValues.length == 0) {
            return "";
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = i + 1 < keyValues.length ? keyValues[i + 1] : null;
            joiner.add(String.valueOf(key) + "=" + value);
        }
        return joiner.toString();
    }

    protected final String buildFailureMessage(String taskName, Object... keyValues) {
        String summary = buildSummary(keyValues);
        if (summary.isEmpty()) {
            return taskName + "执行失败";
        }
        return taskName + "执行失败: " + summary;
    }
}
