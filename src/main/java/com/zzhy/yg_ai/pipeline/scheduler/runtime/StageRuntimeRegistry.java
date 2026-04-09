package com.zzhy.yg_ai.pipeline.scheduler.runtime;

import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class StageRuntimeRegistry {

    private final Map<PipelineStage, StageRuntimeState> states;

    public StageRuntimeRegistry() {
        EnumMap<PipelineStage, StageRuntimeState> runtimeStates = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.values()) {
            runtimeStates.put(stage, new StageRuntimeState());
        }
        this.states = runtimeStates;
    }

    public StageRuntimeState get(PipelineStage stage) {
        return states.get(stage);
    }

    public static final class StageRuntimeState {

        private final AtomicBoolean coordinatorScheduled = new AtomicBoolean(false);
        private final AtomicBoolean triggerPending = new AtomicBoolean(false);
        private final AtomicInteger inFlight = new AtomicInteger(0);
        private final AtomicLong lastSubmitEpochMillis = new AtomicLong(0L);

        public boolean tryMarkCoordinatorScheduled() {
            boolean scheduled = coordinatorScheduled.compareAndSet(false, true);
            if (scheduled) {
                lastSubmitEpochMillis.set(Instant.now().toEpochMilli());
            }
            return scheduled;
        }

        public void markCoordinatorFinished() {
            coordinatorScheduled.set(false);
        }

        public void markTriggered() {
            triggerPending.set(true);
        }

        public void clearTriggered() {
            triggerPending.set(false);
        }

        public int incrementInFlight() {
            return inFlight.incrementAndGet();
        }

        public int decrementInFlight() {
            return inFlight.updateAndGet(current -> Math.max(0, current - 1));
        }

        public int currentInFlight() {
            return inFlight.get();
        }

        public boolean hasPendingTrigger() {
            return triggerPending.get();
        }

        public long lastSubmitEpochMillis() {
            return lastSubmitEpochMillis.get();
        }
    }
}
