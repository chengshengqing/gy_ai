package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.ai.agent.SurveillanceAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 症候群监测症状分类 定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfectiousSyndromeSurveillanceTask {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SurveillanceAgent surveillanceAgent;
    /**
     * 固定延迟执行：每次执行后延迟 1 小时再执行下一次
     */
//    @Scheduled(fixedDelay = 60000)
    public void executeClassify() {
        log.info("症候群监测症状分类任务执行时间：{}", LocalDateTime.now().format(FORMATTER));
        surveillanceAgent.handerSurveillance();
    }
}
