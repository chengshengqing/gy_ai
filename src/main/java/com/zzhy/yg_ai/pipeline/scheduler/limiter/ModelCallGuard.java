package com.zzhy.yg_ai.pipeline.scheduler.limiter;

import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorRedisStore;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModelCallGuard {

    private final Semaphore semaphore;
    private final int totalPermits;
    private final PipelineMonitorRedisStore pipelineMonitorRedisStore;

    public ModelCallGuard(@Value("${infection.pipeline.model-permits:12}") int permits,
                          PipelineMonitorRedisStore pipelineMonitorRedisStore) {
        this.totalPermits = Math.max(1, permits);
        this.semaphore = new Semaphore(this.totalPermits, true);
        this.pipelineMonitorRedisStore = pipelineMonitorRedisStore;
    }

    public <T> T call(PipelineStage stage, Callable<T> action) {
        return call(stage, "UNSPECIFIED", action);
    }

    public <T> T call(PipelineStage stage, String nodeType, Callable<T> action) {
        boolean acquired = false;
        long waitStartedAt = System.currentTimeMillis();
        long acquiredAt = 0L;
        boolean success = false;
        try {
            semaphore.acquire();
            acquired = true;
            acquiredAt = System.currentTimeMillis();
            refreshRuntime();
            T result = action.call();
            success = true;
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for model permit, stage=" + stage, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Model guarded call failed, stage=" + stage, e);
        } finally {
            if (acquired) {
                long finishedAt = System.currentTimeMillis();
                pipelineMonitorRedisStore.recordLlmCall(
                        stage,
                        nodeType,
                        success,
                        Math.max(0L, acquiredAt - waitStartedAt),
                        Math.max(0L, finishedAt - acquiredAt),
                        acquiredAt,
                        finishedAt
                );
                semaphore.release();
                refreshRuntime();
            }
        }
    }

    public void run(PipelineStage stage, Runnable action) {
        call(stage, "UNSPECIFIED", () -> {
            action.run();
            return null;
        });
    }

    private void refreshRuntime() {
        pipelineMonitorRedisStore.refreshModelRuntime(totalPermits, Math.max(0, totalPermits - semaphore.availablePermits()));
    }
}
