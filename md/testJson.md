要求
- 输入信息已经做了按天分组，所以不需要日期信息，日期以dataDate为准
- patBodySurfaceList体征信息，只取异常体温数据
- clinical_notes值取patIllnessCourseList.itemname

输入的示例json：

```
{
  "reqno" : "260218553",
  "dataDate" : "2026-02-25",
  "patInfor" : {
    "reqno" : "260218553",
    "pathosid" : "26021855",
    "sex" : "男",
    "age" : "68岁",
    "disname" : "急诊科",
    "outhodate" : "2026-03-11 13:04:01",
    "inhosday" : "2026-02-25T09:33:00",
    "inhosdistrict" : "心病科",
    "hosdays" : 14
  },
  "patDiagInforList" : [ {
    "reqno" : "260218553",
    "phase" : "其他诊断",
    "diagName" : "硬膜外血肿",
    "diagTime" : "2026-02-25T14:18:12"
  }, {
    "reqno" : "260218553",
    "phase" : "损伤中毒",
    "diagName" : "眩晕",
    "diagTime" : "2026-02-25T14:19:48"
  } ],
  "patBodySurfaceList" : [ {
    "reqno" : "260218553",
    "measuredate" : "2026-02-25T09:50:00",
    "temperature" : "36.5",
    "stoolCount" : null,
    "pulse" : "73.0",
    "breath" : "22.0",
    "bloodPressure" : "144/98"
  }, {
    "reqno" : "260218553",
    "measuredate" : "2026-02-25T16:21:00",
    "temperature" : null,
    "stoolCount" : null,
    "pulse" : null,
    "breath" : null,
    "bloodPressure" : "140/69"
  }],
  "longDoctorAdviceList" : [ {
    "reqno" : "260218553",
    "docadvice" : "病重",
    "begtime" : "2026-02-25T17:06:00",
    "endtime" : "2026-03-05 08:23:32",
    "docadvtype" : "长期",
    "remarks" : null,
    "distname" : "急诊科"
  }, {
    "reqno" : "260218553",
    "docadvice" : "外周静脉留置针护理",
    "begtime" : "2026-02-25T15:41:00",
    "endtime" : "2026-03-11 13:04:28",
    "docadvtype" : "长期",
    "remarks" : null,
    "distname" : "急诊科"
  }],
  "temporaryDoctorAdviceList" : [ {
    "reqno" : "260218553",
    "docadvice" : "5％葡萄糖氯化钠注射液(基） 500ml:25g:4.5g",
    "begtime" : "2026-02-25T16:13:00",
    "endtime" : "2026-02-25 16:23:50",
    "docadvtype" : "临时",
    "remarks" : null,
    "distname" : "急诊科"
  }, {
    "reqno" : "260218553",
    "docadvice" : "静脉输液",
    "begtime" : "2026-02-25T16:13:00",
    "endtime" : "2026-02-25 16:23:50",
    "docadvtype" : "临时",
    "remarks" : null,
    "distname" : "急诊科"
  }],
  "sgDoctorAdviceList" : [ ],
  "patIllnessCourseList" : [ {
    "reqno" : "260218553",
    "illnesscontent" : "</br>2026-02-25 10:16\t首次病程记录</br>    患者陈，男，68岁，农民，于2026-02-25 09:33入院。\r</br>【病例特点】病例特点\r</br>"
	"creattime" : "2026-02-25T10:15:15",
    "itemname" : "首次病程记录"
  }, {
    "reqno" : "260218553",
    "illnesscontent" : "2026-02-25 11:35  危急值处理记录</br>2026-02-25 11:34 接CT室危急值：头颅CT示："
	"creattime" : "2026-02-25T11:31:38",
    "itemname" : "危急值处理记录"
  }],
  "patTestSamList" : [ {
    "reqno" : "260218553",
    "samreqno" : "0512939700",
    "sendtestdate" : "2026-02-25T10:01:00",
    "testaim" : "流感病毒核酸检测两项",
    "dataName" : "咽拭子",
    "testdate" : "2026-02-27T09:07:19",
    "resultList" : [ {
      "reqno" : "260218553",
      "samreqno" : "0512939700",
      "itemname" : "甲型流感病毒RNA检测",
      "engname" : "Flu.A",
      "resultdesc" : "阴性（-）",
      "state" : null,
      "unit" : "阴性(-)",
      "refdesc" : null,
      "allJyFlag" : "正常"
    }, {
      "reqno" : "260218553",
      "samreqno" : "0512939700",
      "itemname" : "乙型流感病毒RNA检测",
      "engname" : "Flu.B",
      "resultdesc" : "阴性（-）",
      "state" : null,
      "unit" : "阴性(-)",
      "refdesc" : null,
      "allJyFlag" : "正常"
    } ]
  }, {
    "reqno" : "260218553",
    "samreqno" : "0512939900",
    "sendtestdate" : "2026-02-25T09:58:00",
    "testaim" : "血常规",
    "dataName" : "全血",
    "testdate" : "2026-02-25T10:50:29",
    "resultList" : [ {
      "reqno" : "260218553",
      "samreqno" : "0512939900",
      "itemname" : "白细胞计数",
      "engname" : "WBC",
      "resultdesc" : "8.13",
      "state" : null,
      "unit" : "3.5-9.5",
      "refdesc" : null,
      "allJyFlag" : "正常"
    }, {
      "reqno" : "260218553",
      "samreqno" : "0512939900",
      "itemname" : "中性粒细胞百分比",
      "engname" : "NEUT%",
      "resultdesc" : "77.50",
      "state" : "↑",
      "unit" : "40-65",
      "refdesc" : null,
      "allJyFlag" : "异常"
    }, {
      "reqno" : "260218553",
      "samreqno" : "0512939900",
      "itemname" : "淋巴细胞百分比",
      "engname" : "LYMPH%",
      "resultdesc" : "11.80",
      "state" : "↓",
      "unit" : "20-50",
      "refdesc" : null,
      "allJyFlag" : "异常"
    }]
  }, {
    "reqno" : "260218553",
    "samreqno" : "0512940000",
    "sendtestdate" : "2026-02-25T09:58:00",
    "testaim" : "D-二聚体+凝血功能",
    "dataName" : "血浆",
    "testdate" : "2026-02-25T11:04:38",
    "resultList" : [ {
      "reqno" : "260218553",
      "samreqno" : "0512940000",
      "itemname" : "国际化标准比值",
      "engname" : "INR",
      "resultdesc" : "1.13",
      "state" : null,
      "unit" : "0.8-1.2",
      "refdesc" : null,
      "allJyFlag" : "正常"
    }, {
      "reqno" : "260218553",
      "samreqno" : "0512940000",
      "itemname" : "凝血酶原时间",
      "engname" : "PT",
      "resultdesc" : "13.50",
      "state" : null,
      "unit" : "10-14",
      "refdesc" : null,
      "allJyFlag" : "正常"
    }]
  }, {
    "reqno" : "260218553",
    "samreqno" : "0512940100",
    "sendtestdate" : "2026-02-25T09:58:00",
    "testaim" : "电解质三项+C-反应蛋白+葡萄糖+心肌酶+血清同型半胱氨酸测定+肝功能1+血脂四项+肾功两项",
    "dataName" : "血清",
    "testdate" : "2026-02-25T11:07:12",
    "resultList" : [ {
      "reqno" : "260218553",
      "samreqno" : "0512940100",
      "itemname" : "丙氨酸氨基转移酶",
      "engname" : "ALT",
      "resultdesc" : "20",
      "state" : null,
      "unit" : "9-50",
      "refdesc" : null,
      "allJyFlag" : "正常"
    }, {
      "reqno" : "260218553",
      "samreqno" : "0512940100",
      "itemname" : "天门冬氨酸氨基转移酶",
      "engname" : "AST",
      "resultdesc" : "42",
      "state" : "↑",
      "unit" : "15-40",
      "refdesc" : null,
      "allJyFlag" : "异常"
    }]
  } ],
  "patUseMedicineList" : [ ],
  "patVideoResultList" : [ {
    "reqno" : "260218553",
    "samreqno" : "124838735",
    "names" : "2026-02-25 09:59:00螺旋CT检查（停）",
    "diagnose" : "1、右侧颞叶见斑片状高密度影，周围密度减低，左侧额叶见斑片状低密度影，右侧额颞顶部见混杂密度影，较厚处约10mm，右侧大脑半球脑沟、脑池变浅，脑中线向左侧移位，约11mm，右侧侧脑室受压，脑干形态大小未见异常。右侧颞顶骨见线样低密度影。扫及扫及双侧上颌窦炎。\r\n2、双侧胸廓对称，右肺见囊状低密度影及结节状密度增高影；气管、支气管显示通畅，未见狭窄及阻塞。纵隔无偏移，纵隔未见肿大淋巴结；心脏及大血管大小形态未见异常，双侧冠脉走行区见条状高密度影。双侧胸膜未见增厚，胸膜腔未见液气胸征象。",
    "testresult" : "1、右侧大脑半球水肿并脑疝形成；\r\n右侧颞叶及左侧额叶脑挫裂伤；右侧额颞顶部硬膜外/下血肿。\r\n右侧颞顶骨骨折。\r\n2、右肺中下叶肺大泡；右肺中叶结节，LU-RADS 2；右肺散在钙化灶。\r\n双侧冠脉走行区钙化灶或术后改变。",
    "reporttime" : "2026-02-25T11:56:34"
  } ],
  "patTransferList" : [ {
    "reqno" : "260218553",
    "indeptdate" : "2026-02-25T09:33:00",
    "indeptname" : "心病科",
    "outhodate" : "2026-02-25"
  }, {
    "reqno" : "260218553",
    "indeptdate" : "2026-02-25T14:42:42",
    "indeptname" : "急诊科",
    "outhodate" : "2026-03-11"
  } ],
  "patOpsCutInforList" : [ ],
  "patTestList" : [ {
    "reqno" : "260218553",
    "testobject" : "肺炎支原体培养及药敏",
    "samreqno" : "0512939800",
    "sampletime" : "2026-02-25T10:00:00",
    "dataName" : "咽拭子",
    "microbeList" : [ {
      "reqno" : "260218553",
      "samreqno" : "0512939800",
      "sendtestdate" : "2026-02-25T10:00:00",
      "samtypename" : "咽拭子",
      "samtype" : "咽拭子",
      "exedate" : "2026-02-27T08:47:02",
      "dataCode" : "6510",
      "result" : "阳性",
      "result1" : null,
      "result2" : null,
      "antiDrugList" : [ {
        "reqno" : "260218553",
        "samreqno" : "0512939800",
        "microbecode" : "6510",
        "mic" : null,
        "dataName" : "依托红霉素",
        "sensitivity" : "敏感"
      }, {
        "reqno" : "260218553",
        "samreqno" : "0512939800",
        "microbecode" : "6510",
        "mic" : null,
        "dataName" : "左氧氟沙星",
        "sensitivity" : "敏感"
      }]
    } ]
  } ],
  "otherInfo" : {
    "queryTime" : "2026-03-16T14:31:11.168655",
    "sourceLastTime" : "2026-03-11T23:35:47.053",
    "dataStartTime" : null
  }
}
```

整理后的示例json ： 

```{
  "reqno":"260218553",
  "dataDate":"2026-02-25",
  "patient_info": {
    "sex": "男",
    "age": 68,
    "admission_time": "2026-02-25 09:33",
    "department": "急诊科"
  },
  "diagnosis": [
    "硬膜外血肿",
    "眩晕"
  ],
  "vital_signs": [
    {
      "time": "09:50",
      "temperature": "36.5",
      "pulse": "73",
      "respiration": "22",
      "blood_pressure": "144/98"
    },
    {
      "time": "16:21",
      "blood_pressure": "140/69"
    }
  ],
  "lab_results": {
    "blood_routine": {
      "WBC": "8.13 (正常)",
      "NEUT%": "77.5 ↑",
      "LYMPH%": "11.8 ↓"
    },
    "coagulation": {
      "INR": "1.13",
      "PT": "13.50"
    },
    "biochemistry": {
      "ALT": "20",
      "AST": "42 ↑"
    },
    "pathogen_test": {
      "肺炎支原体": "阳性",
      "药敏": ["依托红霉素 敏感", "左氧氟沙星 敏感"]
    }
  },
  "imaging": [
    {
      "type": "头颅CT",
      "result": [
        "右侧大脑半球水肿并脑疝形成",
        "右侧颞叶及左侧额叶脑挫裂伤",
        "右侧额颞顶部硬膜外/下血肿",
        "右侧颞顶骨骨折"
      ]
    }
  ],
  "doctor_orders": {
    "long_term": [
      "病重",
      "外周静脉留置针护理"
    ],
    "temporary": [
      "5%葡萄糖氯化钠注射液500ml静滴",
      "静脉输液"
    ]
  },
  "clinical_notes": [
    "首次病程记录",
    "CT危急值报告"
  ]
}
```