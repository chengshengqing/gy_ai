package com.zzhy.yg_ai.pipeline.monitor;

import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.config.PipelineMonitorProperties;
import com.zzhy.yg_ai.mapper.PipelineMonitorQueryMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineBacklogSnapshotScheduler {

    private final PipelineMonitorProperties pipelineMonitorProperties;
    private final PipelineMonitorQueryMapper pipelineMonitorQueryMapper;
    private final PipelineMonitorRedisStore pipelineMonitorRedisStore;

    @Scheduled(fixedDelayString = "${infection.monitor.dashboard.snapshot-fixed-delay-ms:30000}")
    public void snapshotBacklog() {
        if (!pipelineMonitorProperties.isEnabled()) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        try {
            List<PipelineMonitorBacklogRow> rows = pipelineMonitorQueryMapper.selectBacklogRows(now);
            pipelineMonitorRedisStore.saveBacklogSnapshot(rows);
        } catch (Exception e) {
            log.warn("刷新 pipeline backlog 监控快照失败，now={}", now, e);
        }
    }
}
