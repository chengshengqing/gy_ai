package com.zzhy.yg_ai.pipeline.monitor;

import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.pipeline.model.CaseRecomputeResult;
import com.zzhy.yg_ai.pipeline.model.EventExtractResult;
import com.zzhy.yg_ai.pipeline.model.LoadEnqueueResult;
import com.zzhy.yg_ai.pipeline.model.LoadProcessResult;
import com.zzhy.yg_ai.pipeline.model.NormalizeResult;
import java.util.List;
import org.springframework.util.StringUtils;

public final class PipelineMonitorTaskSummaryResolver {

    private PipelineMonitorTaskSummaryResolver() {
    }

    public static String resolveReqnoHint(Object task) {
        if (task instanceof PatientRawDataCollectTaskEntity entity) {
            return normalize(entity.getReqno());
        }
        if (task instanceof PatientRawDataChangeTaskEntity entity) {
            return normalize(entity.getReqno());
        }
        if (task instanceof InfectionEventTaskEntity entity) {
            return normalize(entity.getReqno());
        }
        if (task instanceof List<?> list && !list.isEmpty()) {
            return resolveReqnoHint(list.get(0));
        }
        return null;
    }

    public static PipelineMonitorTaskSummary resolveTaskSummary(Object result) {
        if (result instanceof LoadEnqueueResult loadEnqueueResult) {
            return loadEnqueueResult.success()
                    ? PipelineMonitorTaskSummary.success(null)
                    : PipelineMonitorTaskSummary.failed(null);
        }
        if (result instanceof LoadProcessResult loadProcessResult) {
            return loadProcessResult.success()
                    ? PipelineMonitorTaskSummary.success(loadProcessResult.reqno())
                    : PipelineMonitorTaskSummary.failed(loadProcessResult.reqno());
        }
        if (result instanceof NormalizeResult normalizeResult) {
            if (normalizeResult.failedCount() > 0) {
                return PipelineMonitorTaskSummary.failed(normalizeResult.reqno());
            }
            if (normalizeResult.totalRows() <= 0) {
                return PipelineMonitorTaskSummary.skipped(normalizeResult.reqno());
            }
            return PipelineMonitorTaskSummary.success(normalizeResult.reqno());
        }
        if (result instanceof EventExtractResult eventExtractResult) {
            if (eventExtractResult.failedCount() > 0) {
                return PipelineMonitorTaskSummary.failed(eventExtractResult.reqno());
            }
            if (eventExtractResult.skipped()) {
                return PipelineMonitorTaskSummary.skipped(eventExtractResult.reqno());
            }
            return PipelineMonitorTaskSummary.success(eventExtractResult.reqno());
        }
        if (result instanceof CaseRecomputeResult caseRecomputeResult) {
            if (caseRecomputeResult.failedCount() > 0) {
                return PipelineMonitorTaskSummary.failed(caseRecomputeResult.reqno());
            }
            if (caseRecomputeResult.rescheduled()) {
                return PipelineMonitorTaskSummary.rescheduled(caseRecomputeResult.reqno());
            }
            if (caseRecomputeResult.skipped()) {
                return PipelineMonitorTaskSummary.skipped(caseRecomputeResult.reqno());
            }
            return PipelineMonitorTaskSummary.success(caseRecomputeResult.reqno());
        }
        return null;
    }

    public static PipelineMonitorTaskSummary failedSummary(String reqnoHint) {
        return PipelineMonitorTaskSummary.failed(reqnoHint);
    }

    private static String normalize(String reqno) {
        return StringUtils.hasText(reqno) ? reqno.trim() : null;
    }
}
