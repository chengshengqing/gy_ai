package com.zzhy.yg_ai.pipeline.scheduler.executor;

import com.zaxxer.hikari.HikariDataSource;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorExecutionContextHolder;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorTaskSummary;
import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorRedisStore;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkUnitExecutor implements DisposableBean {

    private static final int RESERVED_JDBC_CONNECTIONS = 1;
    private static final AtomicLong TASK_SEQUENCE = new AtomicLong(0L);

    private final ThreadPoolExecutor executor;
    private final ObjectProvider<StageDispatcher> stageDispatcherProvider;
    private final PipelineMonitorRedisStore pipelineMonitorRedisStore;

    public WorkUnitExecutor(ObjectProvider<StageDispatcher> stageDispatcherProvider,
                            PipelineMonitorRedisStore pipelineMonitorRedisStore,
                            DataSource dataSource,
                            @Value("${infection.pipeline.shared-threads:16}") int configuredThreads) {
        int effectiveThreads = resolveThreads(Math.max(1, configuredThreads), dataSource);
        this.executor = new ThreadPoolExecutor(
                effectiveThreads,
                effectiveThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>());
        this.executor.prestartAllCoreThreads();
        this.stageDispatcherProvider = stageDispatcherProvider;
        this.pipelineMonitorRedisStore = pipelineMonitorRedisStore;
        log.info("初始化共享任务执行器，configuredThreads={}, effectiveThreads={}", configuredThreads, effectiveThreads);
    }

    public void submit(WorkUnit workUnit) {
        executor.execute(new PrioritizedTask(workUnit, executor, stageDispatcherProvider, pipelineMonitorRedisStore));
        refreshExecutorRuntimeSnapshot();
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    private void refreshExecutorRuntimeSnapshot() {
        pipelineMonitorRedisStore.refreshExecutorRuntime(
                executor.getCorePoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size()
        );
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private int resolveThreads(int configuredThreads, DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            return configuredThreads;
        }
        int jdbcPoolSize = Math.max(1, hikariDataSource.getMaximumPoolSize());
        int safeThreads = Math.max(1, jdbcPoolSize - RESERVED_JDBC_CONNECTIONS);
        return Math.min(configuredThreads, safeThreads);
    }

    private static final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {

        private final WorkUnit workUnit;
        private final ThreadPoolExecutor executor;
        private final ObjectProvider<StageDispatcher> stageDispatcherProvider;
        private final PipelineMonitorRedisStore pipelineMonitorRedisStore;
        private final long sequence;

        private PrioritizedTask(WorkUnit workUnit,
                                ThreadPoolExecutor executor,
                                ObjectProvider<StageDispatcher> stageDispatcherProvider,
                                PipelineMonitorRedisStore pipelineMonitorRedisStore) {
            this.workUnit = workUnit;
            this.executor = executor;
            this.stageDispatcherProvider = stageDispatcherProvider;
            this.pipelineMonitorRedisStore = pipelineMonitorRedisStore;
            this.sequence = TASK_SEQUENCE.incrementAndGet();
        }

        @Override
        public void run() {
            long startedAt = System.currentTimeMillis();
            long queueWaitMs = Math.max(0L, startedAt - workUnit.submittedAtEpochMillis());
            PipelineMonitorExecutionContextHolder.bind(new PipelineMonitorExecutionContextHolder.WorkUnitContext(
                    workUnit.stage(),
                    workUnit.unitId(),
                    workUnit.notifyCompletion(),
                    Thread.currentThread().getName(),
                    workUnit.submittedAtEpochMillis(),
                    startedAt
            ));
            pipelineMonitorRedisStore.markWorkUnitStarted(
                    workUnit.stage(),
                    workUnit.unitId(),
                    workUnit.notifyCompletion(),
                    Thread.currentThread().getName(),
                    startedAt
            );
            refreshRuntime();
            try {
                workUnit.execute();
            } catch (RuntimeException | Error e) {
                ensureFailedTaskSummary();
                throw e;
            } finally {
                long finishedAt = System.currentTimeMillis();
                PipelineMonitorExecutionContextHolder.WorkUnitContext context = PipelineMonitorExecutionContextHolder.current();
                if (workUnit.notifyCompletion() && context != null && context.taskSummary() != null) {
                    pipelineMonitorRedisStore.recordTaskSummary(
                            workUnit.stage(),
                            mergeReqnoHint(context.taskSummary(), context.reqnoHint()),
                            queueWaitMs,
                            Math.max(0L, finishedAt - startedAt)
                    );
                }
                pipelineMonitorRedisStore.markWorkUnitFinished(workUnit.unitId());
                PipelineMonitorExecutionContextHolder.clear();
                if (workUnit.notifyCompletion()) {
                    StageDispatcher stageDispatcher = stageDispatcherProvider.getIfAvailable();
                    if (stageDispatcher != null) {
                        stageDispatcher.onWorkUnitCompleted(workUnit.stage());
                    }
                }
                refreshRuntime();
            }
        }

        private void ensureFailedTaskSummary() {
            PipelineMonitorExecutionContextHolder.WorkUnitContext context = PipelineMonitorExecutionContextHolder.current();
            if (context == null || context.taskSummary() != null) {
                return;
            }
            PipelineMonitorExecutionContextHolder.updateTaskSummary(
                    PipelineMonitorTaskSummary.failed(context.reqnoHint())
            );
        }

        private PipelineMonitorTaskSummary mergeReqnoHint(PipelineMonitorTaskSummary taskSummary, String reqnoHint) {
            if (taskSummary == null || taskSummary.reqnoHint() != null || reqnoHint == null) {
                return taskSummary;
            }
            return new PipelineMonitorTaskSummary(reqnoHint, taskSummary.outcome());
        }

        private void refreshRuntime() {
            pipelineMonitorRedisStore.refreshExecutorRuntime(
                    executor.getCorePoolSize(),
                    executor.getActiveCount(),
                    executor.getQueue().size()
            );
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int priorityComparison = Integer.compare(other.workUnit.priority(), this.workUnit.priority());
            if (priorityComparison != 0) {
                return priorityComparison;
            }
            return Long.compare(this.sequence, other.sequence);
        }
    }
}
