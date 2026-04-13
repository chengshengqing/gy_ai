# 患者原始 JSON 结构说明

## 1. 文档用途

本文档用于固定当前 `patient_raw_data.data_json` 与 `patient_raw_data.filter_data_json` 的实际结构，供后续使用 Codex 开发时直接引用。

适用场景：

- 编写基于 `data_json` / `filter_data_json` 的 Agent、规则、转换逻辑
- 给 Codex 提供稳定的结构上下文，避免每次手动描述 JSON 结构
- 排查采集侧字段变更是否影响下游

说明：

- 本文档以当前代码实现为准
- 结构来源的真实代码入口在 `PatientServiceImpl`
- 若后续代码改动，应同步更新本文档

## 2. 结构来源

`data_json` 来源：

- `com.zzhy.yg_ai.service.impl.PatientServiceImpl#buildSemanticBlockJson`

`filter_data_json` 来源：

- `com.zzhy.yg_ai.service.impl.PatientServiceImpl#buildFilterDataJson`
- `com.zzhy.yg_ai.service.impl.PatientServiceImpl#mergeFilterDataJson`
- `com.zzhy.yg_ai.service.impl.PatientServiceImpl#rebuildFilterDataJsonFromRaw`

原始块实体定义来源：

- `com.zzhy.yg_ai.domain.entity.PatientCourseData`

## 3. `data_json` 结构

### 3.1 顶层结构

```json
{
  "reqno": "住院号/就诊流水号",
  "dataDate": "yyyy-MM-dd",
  "admission_time": "yyyy-MM-dd HH:mm",
  "patient_summary": "患者入院时间：2026-04-01，性别：女，年龄：40岁",
  "pat_diagInfor": [],
  "pat_bodySurface": [],
  "pat_doctorAdvice_long": [],
  "pat_doctorAdvice_temporary": [],
  "pat_doctorAdvice_sg": [],
  "pat_illnessCourse": [],
  "pat_testSam": [],
  "pat_useMedicine": [],
  "pat_videoResult": [],
  "pat_transfer": [],
  "pat_opsCutInfor": [],
  "pat_test": []
}
```

说明：

- `data_json` 只保存原始采集块，不做规则压缩
- 当前 `patInfor` 完整对象不写入 `data_json`，只额外写入顶层字段 `admission_time`、`patient_summary`
- `otherInfo` 也不写入 `data_json`

### 3.2 各原始块字段

#### `pat_diagInfor`

```json
{
  "reqno": "string",
  "phase": "string",
  "diagId": "string",
  "diagName": "string",
  "diagTime": "yyyy-MM-ddTHH:mm:ss"
}
```

#### `pat_bodySurface`

```json
{
  "reqno": "string",
  "measuredate": "yyyy-MM-ddTHH:mm:ss",
  "flag": "string",
  "temperature": "string",
  "stoolCount": "string",
  "pulse": "string",
  "breath": "string",
  "bloodPressure": "string"
}
```

#### `pat_doctorAdvice_long` / `pat_doctorAdvice_temporary` / `pat_doctorAdvice_sg`

```json
{
  "reqno": "string",
  "docadvno": "string",
  "docadvice": "string",
  "begtime": "yyyy-MM-ddTHH:mm:ss",
  "endtime": "string",
  "docadvtype": "string",
  "remarks": "string",
  "distname": "string"
}
```

#### `pat_illnessCourse`

```json
{
  "illnessCourseId": "string",
  "reqno": "string",
  "illnesscontent": "string",
  "creattime": "yyyy-MM-ddTHH:mm:ss",
  "changetime": "yyyy-MM-ddTHH:mm:ss",
  "itemname": "string"
}
```

#### `pat_testSam`

```json
{
  "reqno": "string",
  "samreqno": "string",
  "sendtestdate": "yyyy-MM-ddTHH:mm:ss",
  "testaim": "string",
  "dataName": "string",
  "testdate": "yyyy-MM-ddTHH:mm:ss",
  "resultList": [
    {
      "reqno": "string",
      "samreqno": "string",
      "itemno": "string",
      "itemname": "string",
      "engname": "string",
      "resultdesc": "string",
      "state": "string",
      "unit": "string",
      "refdesc": "string",
      "allJyFlag": "string"
    }
  ]
}
```

#### `pat_useMedicine`

```json
{
  "reqno": "string",
  "useorderno": "string",
  "mediId": "string",
  "mediName": "string",
  "medCalss": "string",
  "mediPath": "string",
  "beginTime": "yyyy-MM-ddTHH:mm:ss",
  "zxsj": "string",
  "endTime": "string",
  "mediAim": "string",
  "docadvtype": "string",
  "mediNum": "string",
  "medusage": "string",
  "frequency": "string",
  "unit": "string",
  "memo": "string",
  "distname": "string"
}
```

#### `pat_videoResult`

```json
{
  "reqno": "string",
  "samreqno": "string",
  "itemno": "string",
  "docadvtime": "string",
  "names": "string",
  "diagnose": "string",
  "testresult": "string",
  "reporttime": "yyyy-MM-ddTHH:mm:ss"
}
```

#### `pat_transfer`

```json
{
  "reqno": "string",
  "indeptdate": "yyyy-MM-ddTHH:mm:ss",
  "indeptname": "string",
  "outhodate": "string",
  "outdeptname": "string"
}
```

#### `pat_opsCutInfor`

```json
{
  "reqno": "string",
  "opsId": "string",
  "opsName": "string",
  "begTime": "yyyy-MM-ddTHH:mm:ss",
  "endTime": "string",
  "cutType": "string",
  "hocusMode": "string",
  "preWardMedicineList": [
    {
      "reqno": "string",
      "useorderno": "string",
      "mediId": "string",
      "opsId": "string",
      "mediName": "string",
      "dosage": "string",
      "beginTime": "yyyy-MM-ddTHH:mm:ss"
    }
  ],
  "perioperativeMedicineList": [
    {
      "reqno": "string",
      "useorderno": "string",
      "mediId": "string",
      "opsId": "string",
      "mediName": "string",
      "dosage": "string",
      "beginTime": "yyyy-MM-ddTHH:mm:ss"
    }
  ]
}
```

#### `pat_test`

```json
{
  "reqno": "string",
  "testobject": "string",
  "samreqno": "string",
  "sampletime": "yyyy-MM-ddTHH:mm:ss",
  "dataName": "string",
  "microbeList": [
    {
      "reqno": "string",
      "samreqno": "string",
      "sendtestdate": "yyyy-MM-ddTHH:mm:ss",
      "samtypename": "string",
      "samtype": "string",
      "exedate": "yyyy-MM-ddTHH:mm:ss",
      "dataCode": "string",
      "result": "string",
      "result1": "string",
      "result2": "string",
      "antiDrugList": [
        {
          "reqno": "string",
          "samreqno": "string",
          "microbecode": "string",
          "datano": "string",
          "mic": "string",
          "dataName": "string",
          "sensitivity": "string"
        }
      ]
    }
  ]
}
```

## 4. `filter_data_json` 结构

### 4.1 顶层结构

```json
{
  "reqno": "住院号/就诊流水号",
  "dataDate": "yyyy-MM-dd",
  "admission_time": "yyyy-MM-dd HH:mm",
  "patient_summary": "患者入院时间：2026-04-01，性别：女，年龄：40岁",
  "patient_info": {},
  "diagnosis": [],
  "vital_signs": [],
  "lab_results": {},
  "imaging": [],
  "doctor_orders": {},
  "use_medicine": [],
  "transfer": [],
  "operation": [],
  "clinical_notes": [],
  "pat_illnessCourse": []
}
```

说明：

- `filter_data_json` 是规则处理后的事实块
- 当前是下游摘要、预警链路的主输入之一
- 体征和检验已做结构压缩，但保留正常与异常信息
- 用药、转科、手术信息当前也会进入 `filter_data_json`，但以 compact 事实块形式保存，不再直接暴露原始 `pat_*` 结构

### 4.2 顶层字段说明

#### `admission_time`

```json
"2026-04-01 08:30"
```

说明：

- 由 `patInfor.inhosday` 直接格式化得到
- 当前单独拆出，便于后续按入院时间做计算

#### `patient_summary`

```json
"患者入院时间：2026-04-01，性别：女，年龄：40岁"
```

说明：

- 由 `patInfor.inhosday`、`patInfor.sex`、`patInfor.age` 拼接得到
- 当前用于顶层直接展示，与 `dataDate` 处于同一层级

#### `patient_info`

```json
{
  "sex": "string",
  "age": 68,
  "admission_time": "yyyy-MM-dd HH:mm",
  "department": "string"
}
```

#### `diagnosis`

```json
[
  "诊断1",
  "诊断2"
]
```

说明：

- 由 `pat_diagInfor[].diagName` 去重得到

#### `vital_signs`

体征现在保留所有有值记录，不再只保留异常记录。

```json
[
  {
    "time": "HH:mm",
    "temp": "36.8",
    "stool": "1",
    "pulse": "88",
    "resp": "18",
    "bp": "120/75",
    "abn": []
  },
  {
    "time": "14:00",
    "temp": "38.9",
    "pulse": "122",
    "bp": "88/54",
    "abn": ["temp", "pulse", "bp"]
  }
]
```

字段说明：

- `temp`：体温
- `stool`：大便次数
- `pulse`：脉搏
- `resp`：呼吸
- `bp`：血压
- `abn`：本条记录中异常字段代码列表

#### `lab_results`

```json
{
  "test_panels": [
    {
      "panel_name": "血常规",
      "sample_type": "静脉血",
      "test_time": "yyyy-MM-dd HH:mm",
      "abnormal_count": 2,
      "normal_count": 5,
      "results": [
        {
          "name": "白细胞",
          "value": "12.3",
          "unit": "10^9/L",
          "flag": "高",
          "ref": "3.5-9.5",
          "is_abnormal": true
        },
        {
          "name": "血红蛋白",
          "value": "132",
          "unit": "g/L",
          "flag": "正常",
          "ref": "130-175",
          "is_abnormal": false
        }
      ]
    }
  ],
  "microbe_panels": [
    {
      "panel_name": "痰培养",
      "sample_type": "痰",
      "sample_time": "yyyy-MM-dd HH:mm",
      "abnormal_count": 1,
      "normal_count": 0,
      "results": [
        {
          "organism": "鲍曼不动杆菌",
          "result": "检出",
          "drug_sensitivity": ["亚胺培南 耐药 >=16"],
          "flag": "异常",
          "is_abnormal": true
        }
      ]
    }
  ]
}
```

说明：

- 普通检验与微生物结果都保留正常和异常项
- 已去除对 LLM 价值较低的冗余字段，如 `samreqno`、`eng_name`、`send_test_time`
- `abnormal_count` / `normal_count` 用于快速判断面板整体状态

#### `imaging`

```json
[
  {
    "type": "胸部CT",
    "result": [
      "双肺感染可能",
      "右下肺渗出影"
    ]
  }
]
```

说明：

- `type` 取自 `PatVideoResult.names`
- `result` 是对 `testresult` 按文本规则切分后的列表

#### `doctor_orders`

```json
{
  "long_term": ["长期医嘱1", "长期医嘱2"],
  "temporary": ["临时医嘱1"],
  "sg": ["手工医嘱1"]
}
```

说明：

- 只保留医嘱名称列表，不保留原始时间、备注等字段

#### `use_medicine`

```json
[
  {
    "medication_name": "头孢哌酮舒巴坦",
    "category": "抗菌药物",
    "route": "静滴",
    "dose": "2.0",
    "unit": "g",
    "frequency": "q12h",
    "start_time": "yyyy-MM-dd HH:mm",
    "end_time": "string",
    "purpose": "抗感染",
    "order_type": "长期"
  }
]
```

说明：

- 来源于 `pat_useMedicine`
- 仅保留药名、分类、给药途径、剂量、频次、起止时间、用药目的、医嘱类型等高价值字段
- `start_time` 优先取 `beginTime`，为空时回退 `zxsj`

#### `transfer`

```json
[
  {
    "transfer_time": "yyyy-MM-dd HH:mm",
    "from_department": "急诊科",
    "to_department": "呼吸与危重症医学科"
  }
]
```

说明：

- 来源于 `pat_transfer`
- 仅保留转科时间、转出科室、转入科室
- `transfer_time` 取自 `indeptdate`

#### `operation`

```json
[
  {
    "operation_name": "剖宫产术",
    "operation_time": "yyyy-MM-dd HH:mm",
    "operation_end_time": "string",
    "cut_type": "II类切口",
    "anesthesia_mode": "腰硬联合麻醉",
    "pre_ward_medicines": [
      {
        "medication_name": "头孢唑啉",
        "dose": "1.0g",
        "start_time": "yyyy-MM-dd HH:mm"
      }
    ],
    "perioperative_medicines": [
      {
        "medication_name": "头孢唑啉",
        "dose": "1.0g",
        "start_time": "yyyy-MM-dd HH:mm"
      }
    ]
  }
]
```

说明：

- 来源于 `pat_opsCutInfor`
- 保留手术名称、开始结束时间、切口类型、麻醉方式
- 术前用药和围术期用药会被压缩为药名、剂量、开始时间三个字段

#### `clinical_notes`

```json
[
  "首次病程记录",
  "主任医师查房记录",
  "会诊记录"
]
```

说明：

- 由过滤后的 `pat_illnessCourse[].itemname` 去重得到

#### `pat_illnessCourse`

```json
[
  {
    "illnessCourseId": "string",
    "reqno": "string",
    "illnesscontent": "过滤后的病程内容",
    "creattime": "yyyy-MM-ddTHH:mm:ss",
    "changetime": "yyyy-MM-ddTHH:mm:ss",
    "itemname": "病程类型"
  }
]
```

说明：

- 结构仍沿用 `PatientCourseData.PatIllnessCourse`
- `illnesscontent` 已经过 `filterIllnessCourseList` 过滤
- 首次病程会被替换为标准化后的首程内容

## 5. 当前压缩策略

### `data_json`

- 不做结构压缩
- 以原始采集块为主
- 用于回溯、重建、增量 merge

### `filter_data_json`

- 面向 LLM 与规则消费
- 保留正常值和异常值
- 删除对推理价值较低的冗余字段
- 对体征、检验、用药、转科、手术做轻量结构压缩，降低 token 长度

## 6. 给 Codex 的推荐引用方式

后续开发时可直接在需求中引用：

```text
请以 docs/data/patient-raw-json-structure.md 作为 data_json 和 filter_data_json 的结构依据。
如代码实现与文档冲突，以 PatientServiceImpl 当前实现为准，并同步更新文档。
```
