package com.zzhy.yg_ai.pipeline.scheduler.limiter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorRedisStore;
import org.junit.jupiter.api.Test;

class ModelCallGuardTest {

    @Test
    void constructorDoesNotTouchMonitorStore() {
        PipelineMonitorRedisStore pipelineMonitorRedisStore = mock(PipelineMonitorRedisStore.class);

        new ModelCallGuard(4, pipelineMonitorRedisStore);

        verifyNoInteractions(pipelineMonitorRedisStore);
    }
}
