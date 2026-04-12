package com.zzhy.yg_ai.pipeline.scheduler.policy;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StagePolicyRegistry {

    private final Map<PipelineStage, StagePolicy> normalPolicies;
    private final Map<PipelineStage, StagePolicy> normalizeWindowPolicies;
    private final Set<Integer> normalizeWindowStartHours;
    private final int normalizeWindowDurationHours;
    private volatile boolean lastWindowState = false;

    public StagePolicyRegistry(
            @Value("${infection.pipeline.normalize-window-start-hours:0,12}") String windowStartHours,
            @Value("${infection.pipeline.normalize-window-duration-hours:4}") int windowDurationHours) {
        this.normalizeWindowStartHours = parseHours(windowStartHours);
        this.normalizeWindowDurationHours = Math.max(1, Math.min(windowDurationHours, 12));
        this.normalPolicies = buildNormalPolicies();
        this.normalizeWindowPolicies = buildNormalizeWindowPolicies();
        log.info("策略注册表初始化完成，normalizeWindowStartHours={}, normalizeWindowDurationHours={}",
                this.normalizeWindowStartHours, this.normalizeWindowDurationHours);
    }

    private static Map<PipelineStage, StagePolicy> buildNormalPolicies() {
        EnumMap<PipelineStage, StagePolicy> policies = new EnumMap<>(PipelineStage.class);
        policies.put(PipelineStage.LOAD_ENQUEUE,
                new StagePolicy(PipelineStage.LOAD_ENQUEUE, 100, 1, 1));
        policies.put(PipelineStage.LOAD_PROCESS,
                new StagePolicy(PipelineStage.LOAD_PROCESS, 90, 3, 3));
        policies.put(PipelineStage.NORMALIZE,
                new StagePolicy(PipelineStage.NORMALIZE, 80, 2, 4));
        policies.put(PipelineStage.EVENT_EXTRACT,
                new StagePolicy(PipelineStage.EVENT_EXTRACT, 70, 8, 8));
        policies.put(PipelineStage.CASE_RECOMPUTE,
                new StagePolicy(PipelineStage.CASE_RECOMPUTE, 60, 3, 4));
        return Map.copyOf(policies);
    }

    private static Map<PipelineStage, StagePolicy> buildNormalizeWindowPolicies() {
        EnumMap<PipelineStage, StagePolicy> policies = new EnumMap<>(PipelineStage.class);
        policies.put(PipelineStage.LOAD_ENQUEUE,
                new StagePolicy(PipelineStage.LOAD_ENQUEUE, 100, 1, 1));
        policies.put(PipelineStage.LOAD_PROCESS,
                new StagePolicy(PipelineStage.LOAD_PROCESS, 80, 1, 1));
        policies.put(PipelineStage.NORMALIZE,
                new StagePolicy(PipelineStage.NORMALIZE, 95, 9, 9));
        policies.put(PipelineStage.EVENT_EXTRACT,
                new StagePolicy(PipelineStage.EVENT_EXTRACT, 60, 9, 9));
        /*policies.put(PipelineStage.NORMALIZE,
                new StagePolicy(PipelineStage.NORMALIZE, 95, 8, 8));
        policies.put(PipelineStage.EVENT_EXTRACT,
                new StagePolicy(PipelineStage.EVENT_EXTRACT, 60, 2, 2));*/
        policies.put(PipelineStage.CASE_RECOMPUTE,
                new StagePolicy(PipelineStage.CASE_RECOMPUTE, 50, 1, 2));
        return Map.copyOf(policies);
    }

    public StagePolicy getPolicy(PipelineStage stage) {
        boolean inWindow = isNormalizePreferredWindow();
        logWindowTransition(inWindow);
        Map<PipelineStage, StagePolicy> policies = inWindow ? normalizeWindowPolicies : normalPolicies;
        return policies.get(stage);
    }

    public boolean isNormalizePreferredWindow() {
        int currentHour = LocalTime.now().getHour();
        for (int startHour : normalizeWindowStartHours) {
            int endHour = startHour + normalizeWindowDurationHours;
            if (endHour <= 24) {
                if (currentHour >= startHour && currentHour < endHour) {
                    return true;
                }
            } else {
                if (currentHour >= startHour || currentHour < (endHour % 24)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void logWindowTransition(boolean currentWindowState) {
        if (currentWindowState != lastWindowState) {
            lastWindowState = currentWindowState;
            if (currentWindowState) {
                log.info("进入 NORMALIZE 优先窗口，调度策略已切换");
            } else {
                log.info("退出 NORMALIZE 优先窗口，调度策略已恢复为普通时段");
            }
        }
    }



    private static Set<Integer> parseHours(String hoursStr) {
        Set<Integer> hours = new LinkedHashSet<>();
        if (hoursStr == null || hoursStr.isBlank()) {
            return hours;
        }
        for (String part : hoursStr.split(",")) {
            try {
                int h = Integer.parseInt(part.trim());
                if (h >= 0 && h < 24) {
                    hours.add(h);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return hours;
    }
}
