package com.zzhy.yg_ai.task;

import com.zzhy.yg_ai.service.react.ReactMedicalStructService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 院感定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicalStructScheduledTask {
    private final ReactMedicalStructService reactMedicalStructService;


    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 固定延迟执行：每次执行后延迟 5 秒再执行下一次
     */
//    @Scheduled(fixedDelay = 5000)
    public void executeWithFixedDelay() {
        log.info("固定延迟任务执行时间：{}", LocalDateTime.now().format(FORMATTER));
    }

    /**
     * 固定频率执行：每隔 10 秒执行一次
     */
//    @Scheduled(fixedRate = 10000)
    public void executeWithFixedRate() {
        log.info("固定频率任务执行时间：{}", LocalDateTime.now().format(FORMATTER));
    }

    /**
     * 使用 Cron 表达式：每分钟的第 0 秒执行
     */
//    @Scheduled(cron = "0 * * * * ?")
    public void executeWithCron() {
        log.info("Cron 定时任务执行时间：{}", LocalDateTime.now().format(FORMATTER));
    }

    /**
     * 一级分类
     * 固定延迟执行：每次执行后延迟 1 小时再执行下一次
     */
//    @Scheduled(fixedDelay = 60000) // 1 小时 = 60 分钟 × 60 秒 × 1000 毫秒
    public void executeClassifyData() {
        log.info("一级分类任务执行时间：{}", LocalDateTime.now().format(FORMATTER));
        reactMedicalStructService.executeClassifyData();
    }

    /**
     * 二级事件抽取
     * 固定延迟执行：每次执行后延迟 1 小时再执行下一次
     */
//    @Scheduled(fixedDelay = 60000)
    public void executeMedicalEvent() {
        log.info("二级事件抽取任务执行时间：{}", LocalDateTime.now().format(FORMATTER));
        reactMedicalStructService.executeMedicalEvent();
    }

    /**
     * 病程结构化
     * 固定延迟执行：每次执行后延迟 1 小时再执行下一次
     */
//    @Scheduled(fixedDelay = 60000)
    public void executeMedicalStructured() {
        log.info("病程格式化任务执行时间：{}", LocalDateTime.now().format(FORMATTER));
        reactMedicalStructService.executeMedicalStructured();
    }


}
