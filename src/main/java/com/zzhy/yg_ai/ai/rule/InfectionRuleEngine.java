package com.zzhy.yg_ai.ai.rule;

import org.springframework.stereotype.Component;

@Component
public class InfectionRuleEngine {
    public void analyze(Long patientId, String structuredJson) {

        // TODO: 解析JSON
        // TODO: 判断48小时规则
        // TODO: 判断侵入操作
        // TODO: 写入数据库

        System.out.println("院感分析完成");
    }
}
