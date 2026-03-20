根据任务完成以下编码工作：

调用SummaryAgent、WarningAgent完成以下任务，以定时任务方式开启。

1.查看患者每日病程，参考以下查询方法。查找struct_data_json不为null的数据。

```
    public List<PatientRawDataEntity> listPendingStructRawData(int limit) {
        int size = limit <= 0 ? 50 : limit;
        return patientRawDataMapper.selectList(new QueryWrapper<PatientRawDataEntity>()
                .isNotNull("struct_data_json")
                .orderByAsc("id")
                .last("OFFSET 0 ROWS FETCH NEXT " + size + " ROWS ONLY"));
    }
```

2.LLM事件提取（SummaryAgent）。

将查询到的信息输入LLM，system_prompt为:SummaryAgentPrompt.TIME_EVENT_PROMPT。LLM返回的结果示例为：

```
输出JSON结构如下：
            
            {
              "symptoms": [],
              "vital_abnormal": [],
              "abnormal_labs": [],
              "pathogen_results": [],
              "antibiotics": [],
              "devices": [],
              "procedures": [],
              "imaging_findings": []
            }
            
            每个事件对象结构示例：
            
            symptoms 示例：
            {
              "name": "发热",
              "time": "2026-03-05"
            }
            
            abnormal_labs 示例：
            {
              "item": "WBC",
              "value": "12.5",
              "flag": "↑",
              "time": "2026-03-05"
            }
            
            pathogen_results 示例：
            {
              "pathogen": "肺炎支原体",
              "result": "阳性",
              "time": "2026-03-05"
            }
            
            antibiotics 示例：
            {
              "drug": "左氧氟沙星",
              "time": "2026-03-05"
            }
            
            devices 示例：
            {
              "device": "导尿管",
              "time": "2026-03-05"
            }
            
            procedures 示例：
            {
              "name": "腰椎穿刺",
              "type": "procedure",
              "time": "2026-03-05"
            }
            
            imaging_findings 示例：
            {
              "finding": "肺部感染",
              "time": "2026-03-05"
            }
```

3.事件存储 (Redis)

在第1步查询患者信息时，会查到PatientRawDataEntity.dataDate。将该参数作为时间线，把第2步中通过LLM提取到的该患者信息存入redis。每次进行累加存储。最终 Redis 形成：

```
patient_events:{patient_id}

#包含：
symptoms
labs
pathogens
devices
procedures
antibiotics
imaging
```

4.每天0点启动另一个定时任务，将redis中的内容同步到数据库中：PatientSummaryEntity。

5.用WarningAgent进行院感预警分析，从redis中拿到患者时间线数据（若数据库无数据，则从PatientSummaryEntity中取），system_prompt为:WarningAgentPrompt.EVALUATE_PROMPT，LLM会输出分析结果。将结果存入数据库：InfectionAlertEntity。
