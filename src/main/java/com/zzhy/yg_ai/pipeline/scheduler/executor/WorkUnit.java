package com.zzhy.yg_ai.pipeline.scheduler.executor;

import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import java.util.Objects;

public class WorkUnit {

    private final String unitId;
    private final PipelineStage stage;
    private final int priority;
    private final Runnable action;
    private final boolean notifyCompletion;
    private final long submittedAtEpochMillis;

    public WorkUnit(String unitId,
                    PipelineStage stage,
                    int priority,
                    Runnable action) {
        this(unitId, stage, priority, action, true);
    }

    public WorkUnit(String unitId,
                    PipelineStage stage,
                    int priority,
                    Runnable action,
                    boolean notifyCompletion) {
        this.unitId = Objects.requireNonNull(unitId, "unitId");
        this.stage = Objects.requireNonNull(stage, "stage");
        this.priority = priority;
        this.action = Objects.requireNonNull(action, "action");
        this.notifyCompletion = notifyCompletion;
        this.submittedAtEpochMillis = System.currentTimeMillis();
    }

    public String unitId() {
        return unitId;
    }

    public PipelineStage stage() {
        return stage;
    }

    public int priority() {
        return priority;
    }

    public void execute() {
        action.run();
    }

    public boolean notifyCompletion() {
        return notifyCompletion;
    }

    public long submittedAtEpochMillis() {
        return submittedAtEpochMillis;
    }
}
