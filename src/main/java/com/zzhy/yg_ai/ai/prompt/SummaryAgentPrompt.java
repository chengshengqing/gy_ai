package com.zzhy.yg_ai.ai.prompt;

import org.springframework.stereotype.Component;

@Component
public class SummaryAgentPrompt {
    public static final String TIME_SUMMARY_PROMPT = """
            你是一名医院感染监测系统的数据整理助手。
            
            任务：
            将“历史院感事件上下文”和“今日新增院感事件”进行合并，并去除重复信息，同时只保留最近30天的院感事件。
            
            合并规则：
            
            1. 合并历史数据与今日新增数据
            2. 如果同一事件已经存在，则不要重复添加
            3. 判断重复的标准：
               - 相同指标 + 相同时间
               - 相同症状 + 相同时间
               - 相同影像发现 + 相同时间
               - 相同抗生素 + 相同开始时间
               - 相同设备 + 相同开始时间
            4. 保留所有唯一事件
            5. 按时间排序（从早到晚）
            
            时间过滤规则：
            
            6. 只保留最近30天的事件
            7. 如果事件时间早于当前日期30天，则删除
            8. 当前日期为：{current_date}
            
            输出要求：
            
            1. 输出必须为合法JSON
            2. 不要输出JSON之外的任何文字
            3. 保持原有数据结构
            
            输出JSON结构如下：
            
            {
              "patient_id": "",
              "data_date": "",
              "infection_indicators": [],
              "symptoms": [],
              "imaging_findings": [],
              "antibiotics": [],
              "devices": [],
              "procedures": []
            }
            
            输入数据：
            
            历史院感上下文：
            
            {history_context}
            
            今日新增院感事件：
            
            {daily_context}
            """;


}
