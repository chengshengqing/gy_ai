## 创建对应的实体类、service、mapper、xml

要求：

- 只创建一套实体类、service、mapper、xml，所有代码都写在这一套中
- 查询患者信息（PatInfor）后，会得到reqno字段，这是患者编号，后续的所有查询中的reqno都是这样
- 查询到的患者信息，在java代码中。按每一天分组，转为json格式，存入PatientRawDataEntity中。每一天数据为一个json，对应一条数据库记录。
- 信息更新时间查询，可以获取到last_time，每次查询患者信息时，如果PatientRawDataEntity中存在对应的reqno，则要查询大于等于last_time的数据

### 1.创建一个患者信息的实体类，里面有多个子类，分别是：

- 患者信息（PatInfor）
- 诊断信息（patdiaginfor）
- 体征（PatBodySurface）
- 医嘱（patdoctoradvice）
- 病例（patillnesscourse）
- 检验（pattestsam）
- 用药（patusemedicine）
- 影像（patvideoresult）
- 转科（pat_infor_pats_basis）
- 手术（patopscutinfor）
- 微生物（pattest）
- 其他信息（otherinfo）

### 2.每个子类的信息以及对应的sql语句，每个子类都有reqno字段，用reqno进行关联查询。参考下面的方式：

```
<resultMap id="BaseResultMap" type="com.zzhy.yg_ai.domain.dto.PatillnessCourseInfo">
        <id column="reqno" property="reqno"/>
        <result column="pathosid" property="pathosid"/>
        <result column="patname" property="patname"/>
        <collection property="diagInfoList" select="selectDiagByReqno" column="reqno"
                    ofType="com.zzhy.yg_ai.domain.dto.PatillnessCourseInfo$DiagInfo"/>
        <collection property="illnessInfoList" select="selectIllnessByReqno" column="reqno"
                    ofType="com.zzhy.yg_ai.domain.dto.PatillnessCourseInfo$IllnessInfo"/>
    </resultMap>
```

#### 信息更新时间查询

```
#last_time是信息更新的最后时间
select top 1 last_time from item_time_set order by last_time desc
```



#### 患者信息（PatInfor）

sql语句：

```
SELECT
    reqno, --患者主键
    pathosid, --患者id
    sex, --性别
    age + ageunit AS age, --年龄
    d1.data_name AS disname, --当前科室
    CASE
        WHEN CONVERT(VARCHAR(10), outhodate, 120) = '9999-12-31' THEN '在院'
        ELSE CONVERT(VARCHAR(19), outhodate, 120)
    END AS outhodate, --出院日期
    inhosdate AS inhosday, --入院日期
    d2.data_name AS inhosdistrict, -- 入院科室
    DATEDIFF(DAY, inhosdate, CASE WHEN outhodate = '9999-12-31' THEN CURRENT_TIMESTAMP ELSE outhodate END) AS hosdays --入院天数
FROM
    pat_infor pat
LEFT JOIN
    district_dict d1 ON pat.currentdist = d1.datano
LEFT JOIN
    district_dict d2 ON pat.inhosdict = d2.datano
LEFT JOIN
    district_custom d3 ON pat.currentdept = d3.datano
WHERE
    1 = 1
and  (
     (COALESCE(NULLIF('260302506', '_'), '_') = '_' OR pat.reqno = '260302506')
      or
     (
	   (COALESCE(NULLIF('', '_'), '_') = '_' OR pat.pathosid = '') and  (COALESCE(NULLIF('', '_'), '_') = '_' OR pat.inhossum = '')
        or
        ( COALESCE(NULLIF('', '_'), '_') = '_' OR pat.visit_id = '' and  (COALESCE(NULLIF('', '_'), '_') = '_' OR pat.inhossum = '')
       )
     )
)
and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d1.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
```

#### 诊断信息（patdiaginfor）

sql语句：

```
select a.phase, --类型
       a.diag_name, --具体事项
       a.diag_time --诊断时间
from patdiaginfor a
inner join pat_infor p on a.reqno=p.reqno
inner join district_dict d on p.currentdist=d.datano
where a.reqno='260302506'
and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
order by a.diag_time
```

#### 体征（PatBodySurface）

sql语句：

```
SELECT
    pbs.measuredate, -- 测量时间
    MAX(CASE WHEN pbs.flag = '体温' THEN pbs.temperature END) AS temperature,
    MAX(CASE WHEN pbs.flag = '大便次数' THEN pbs.temperature END) AS stool_count,
    MAX(CASE WHEN pbs.flag = '脉搏' THEN pbs.temperature END) AS pulse,
    MAX(CASE WHEN pbs.flag = '呼吸' THEN pbs.temperature END) AS breath,
    CONCAT(
        MAX(CASE WHEN pbs.flag = '血压'
            THEN CASE
                WHEN pbs.temperature = -1 THEN NULL
                ELSE CAST(pbs.temperature AS VARCHAR)
            END
        END),
        '/',
        MAX(CASE WHEN pbs.flag = '舒张压'
            THEN CASE
                WHEN pbs.temperature = -1 THEN NULL
                ELSE CAST(pbs.temperature AS VARCHAR)
            END
        END)
    ) AS blood_pressure
FROM PatBodySurface pbs
where reqno = '260221118'
GROUP BY
    pbs.reqno, pbs.measuredate;
```

#### 医嘱（patdoctoradvice）

sql语句：

##### 长期医嘱

```
SELECT
    docadvice, --医嘱名称
    begtime, --开始时间
    CASE
        WHEN CONVERT(VARCHAR(10), endtime, 120) IN ('9999-12-31', '1900-01-01') THEN '未结束'
        ELSE CONVERT(VARCHAR(19), endtime, 120)
    END AS endtime, --结束时间
    docadvtype, --医嘱类型
    memo AS remarks, --备注
    d.data_name as distname --执行科室
FROM
    patdoctoradvice p
    inner join district_dict d on  p.distno=d.datano
WHERE
    docadvtype = '长期'
    AND reqno = '260302506'
    AND (COALESCE('_', '_') = '_' OR docadvice LIKE '%' + '_' + '%')
    and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)

ORDER BY  begtime DESC
```

##### 临时医嘱

```
SELECT
    docadvice,--医嘱名称
    begtime,--开始时间
    CASE
        WHEN CONVERT(VARCHAR(10), endtime, 120) IN ('9999-12-31', '1900-01-01') THEN '未结束'
        ELSE CONVERT(VARCHAR(19), endtime, 120)
    END AS endtime,--结束时间
    docadvtype,--医嘱类型
    memo AS remarks,--备注
    d.data_name as distname--执行科室
FROM
    patdoctoradvice  p
    inner join district_dict d on  p.distno=d.datano
WHERE
    (COALESCE('临时', '_') = '_' OR docadvtype = '临时')
    AND reqno = '260302506'
    AND (COALESCE('_', '_') = '_' OR docadvice LIKE '%' + '_' + '%')
    and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)

ORDER BY begtime DESC
```

##### 三管医嘱

```
SELECT
    docadvice,--医嘱名称
    begtime,--开始时间
    CASE
        WHEN CONVERT(VARCHAR(10), endtime, 120) IN ('9999-12-31', '1900-01-01') THEN '未结束'
        ELSE CONVERT(VARCHAR(19), endtime, 120)
    END AS endtime,--结束时间
    docadvtype,--医嘱类型
    memo AS remarks,--备注
    d.data_name as distname--执行科室
FROM
    patdoctoradvice p
    inner join district_dict d on  p.distno=d.datano
WHERE
    (COALESCE('_', '_') = '_' OR docadvtype = '_')
    AND reqno = '260221118'
    AND (COALESCE('_', '_') = '_' OR docadvice LIKE '%' + '_' + '%')
    AND sg_flag IS NOT NULL
    and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)

ORDER BY  begtime DESC
```

#### 病例（patillnesscourse）

sql语句：

```
SELECT
    i.illnesscontent, --病程信息
    i.creattime, --创建时间
    i.itemname --病程类型
    FROM
    patillnesscourse i
    inner join pat_infor p on i.reqno=p.reqno
inner join district_dict d on p.currentdist=d.datano
WHERE
    1 = 1
    AND i.reqno = '260221118'
    and(COALESCE(NULLIF('', '_'), '') = '' OR i.id = '')
    and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
```

#### 检验（pattestsam）

检验列表sql语句：

```
select
	    samreqno, --样本编号
sendtestdate, --送检时间
testaim, --送检目的
 samtype data_name, --标本类型
testdate --检验时间
from pattestsam pt
inner join district_dict d on  pt.distno=d.datano
where reqno='260221118'
and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)

order by sendtestdate desc
```

每个样本的检验结果，用samreqno字段关联，sql语句：

```
select
    pm.itemname, --检验项目
    engname, --英文名称
    resultdesc, --检验结果
    state, --状态
    unit, --单位
    refdesc, --参考范围
CASE WHEN   pm.state <> '正常' THEN '异常' ELSE '正常' END AS all_jy_flag --检验结果
from pattestsam pt
inner join pattestresult pm on pt.reqno=pm.reqno  and pt.samreqno=pm.samreqno
inner join district_dict d on  pt.distno=d.datano
where 1=1
and pt.reqno='260221118'

and pt.samreqno='0513399100'
and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
```

#### 用药（patusemedicine）

sql语句：

```
SELECT
    medi_name, --药品名称
    med_calss, -- 药品分类
    medi_path, --给药途径
    begin_time, --开始时间
    convert(varchar(19),zxsj,120) as zxsj, --执行时间
    CASE
        WHEN CONVERT(VARCHAR(10), end_time, 120) IN ('9999-12-31', '1900-01-01') THEN '未结束'
        ELSE CONVERT(VARCHAR(19), end_time, 120)
    END AS end_time, --结束时间
    medi_aim, --用药目的
    docadvtype, --用药周期
    dosage AS medi_num, -- 计量
    medusage, -- 用量
    frequency, -- 频次
    unit, -- 单位
    memo, -- 备注
    d.data_name as distname --执行科室
FROM
    patusemedicine  p
    inner join district_dict d on  p.distno=d.datano
WHERE
    1 = 1
    AND docadvtype = '长期'
    AND reqno = '260221118'
    and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)

ORDER BY
    begin_time DESC
```

#### 影像（patvideoresult）

影像列表sql语句：

```
SELECT
      pd.samreqno, --影像编号
    CONVERT(VARCHAR, docadvtime, 120) + itemname AS names, --影像名称
    pd.diagnose, -- 影像描述
    testresult, --检查结果
    CONVERT(VARCHAR, reporttime, 120) AS reporttime --报告时间
FROM
    patvideoresult pd
    inner join pat_infor p on pd.reqno=p.reqno
    inner join district_dict d on  p.currentdist=d.datano
WHERE
    1 = 1
    AND pd.reqno = '260221118'
  and(COALESCE(NULLIF('', '_'), '') = '' OR CONVERT(VARCHAR, reporttime, 112) = '')
  and(COALESCE(NULLIF('', '_'), '') = '' OR pd.samreqno = '')
  and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)

ORDER BY
    reporttime
```

#### 转科（pat_infor_pats_basis）

sql语句：

```
SELECT
	a_inhos_date AS indeptdate, --入科时间
	a_data_name AS indeptname, --入科科室
	CASE
         WHEN CONVERT(VARCHAR(10), a_outhos_date, 120) = '9999-12-31' THEN '在院'
         ELSE CONVERT(VARCHAR(10), a_outhos_date, 23)
         END AS outhodate --出科时间
FROM	pat_infor_pats_basis p
inner join district_dict d on p.a_distno=d.datano
WHERE reqno = '260221118'
and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
```

#### 手术（patopscutinfor）

sql语句，通过ops_id与手术用药关联

```
SELECT
    ops.ops_id,  --手术id
    ops.ops_name, --手术名称
    ops.beg_time, --手术开始时间
    CASE
        WHEN CONVERT(VARCHAR(10), ops.end_time, 120) IN ('9999-12-31', '1900-01-01') THEN '未结束'
        ELSE CONVERT(VARCHAR(19), ops.end_time, 120)
    END AS end_time, --手术结束时间
    ops.cut_type, --切口类型
    hocus_mode --麻醉方式
FROM
    patopscutinfor ops
    inner join district_dict d on  ops.opsdept=d.datano
WHERE
    1 = 1
    AND ops.pathosid IN (SELECT pathosid FROM pat_infor WHERE reqno = '260307212')
    AND DATEDIFF(DAY, ops.beg_time, CURRENT_TIMESTAMP) <= 365
    and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
```

术前(病房)用药sql语句：

```
select med.medi_name, --药品名称
       med.dosage+med.unit as dosage, --剂量
       med.begin_time --用药开始时间
from patopscutinfor ops
inner join patusemedicine med on ops.reqno=med.reqno
-- and med.begin_time between ops.beg_time-7 and ops.beg_time
inner join district_dict d on  ops.opsdept=d.datano
where ops.reqno='260307212'
 and ops.ops_id= '2603072121'
 and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
```

围手术期用药sql语句：

```
select med.medi_name, --药品名称
       med.dosage+med.unit as dosage, --剂量
       med.begin_time --用药开始时间
from patopscutinfor ops
inner join patusemedicine_ops med on ops.reqno=med.reqno and ops.ops_id=med.ops_id
inner join district_dict d on  ops.opsdept=d.datano
where ops.reqno='260307212'
and ops.ops_id= '2603072121'
and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
```

#### 微生物（pattest）

sql语句，通过samreqno与细菌列表关联：

```
select
pte.testobject, -- 送检目的
pte.samreqno, -- 标本编号
pte.sampletime, -- 送检时间
pte.samtype_name as data_name --标本类型

from pattest pte
left join  patmicrobesource ps
on   pte.reqno = ps.reqno AND pte.samreqno = ps.samreqno
left join district_dict d on pte.dist=d.datano
where 1=1
and pte.reqno='260304621'
and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)

order by sampletime desc
```

细菌列表sql语句，通过samreqno关联，data_code与药物列表关联：

```
select  * from (
SELECT
    pt.samreqno,
    pt.sampletime AS sendtestdate, --送检时间
    pt.samtype_name AS samtypename, --标本名称
    pt.samtype_name AS samtype, --标本类型
    pm.exedate, --报告时间
    pm.data_code, -- 关联药物code
    CASE
        WHEN ps.reqno IS NOT NULL AND pm.reqno IS NOT NULL THEN '阳性'
        ELSE '阴性'
    END AS result --结果
        ,case when ps.reqno IS not null and ps.memo  is not null then '多重耐药'  end AS result1 --多重耐药
    ,case when ps.reqno IS  null and pm.memo  is not null then '细菌备注' end AS result2 --备注
FROM
    pattest pt
INNER JOIN
    patmicrobesource_rep pm ON pt.reqno = pm.reqno AND pt.samreqno = pm.samreqno
LEFT JOIN (
    SELECT reqno, samreqno,data_code,memo
    FROM patmicrobesource
    WHERE reqno = '260304621'
    GROUP BY reqno, samreqno,data_code,memo
) AS ps ON pt.reqno = ps.reqno AND pt.samreqno = ps.samreqno and pm.data_code=ps.data_code
left join (
    select reqno,samreqno,microbecode,count(*) as nys
    from patantisource
    where 1=1
    and reqno='260304621'
    and(COALESCE(NULLIF('0514534200', '_'), '') = '' OR  samreqno = '0514534200')
    and(COALESCE(NULLIF('', '_'), '') = '' OR  microbecode = '')
    GROUP BY reqno,samreqno,microbecode
) AS pts ON pt.reqno = pts.reqno AND pt.samreqno = pts.samreqno and pts.microbecode=ps.data_code

LEFT JOIN
    district_dict d ON pt.dist = d.datano
LEFT JOIN
    pat_infor pat ON pat.reqno = pt.reqno
WHERE
    1 = 1
    AND pt.reqno = '260304621'
    AND (
        (COALESCE(NULLIF('0514534200', '_'), '') = '' AND ps.reqno IS NOT NULL AND pm.reqno IS NOT NULL)
        OR
        (COALESCE(NULLIF('0514534200', '_'), '') != '' AND pm.samreqno = '0514534200')
    )
    and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
) t2
ORDER BY
    sendtestdate DESC
```

药物列表sql语句，microbecode关联细菌列表sql中的data_code：

```
select
       a.micresult mic, --mic结果
       a.data_name, --药物名称
       a.micexplain sensitivity --敏感度
from patantisource a
inner join pat_infor p on a.reqno=p.reqno
inner join district_dict d on p.currentdist=d.datano
left  join main_antiitem c on a.datano=c.datano
where 1=1
and a.reqno='260304621'
and(COALESCE(NULLIF('0514534200', '_'), '') = '' OR  a.samreqno = '0514534200')
and(COALESCE(NULLIF('610', '_'), '') = '' OR  a.microbecode = '610')
and ( coalesce('0','0')='0'
			or (coalesce('0','0')='1'
			and (coalesce(d.depcss,'_')='本院' or coalesce('本院','_')='_'))

			)
 order by item_memo1 DESC
```

#### 

注意：

- 我的sql语句都是正确的，只可修改reqno、samreqno、data_code、ops_id等参数值为动态参数，再添加按时间查询，不可改动其他地方。
- 所有sql语句放入同一个xml中

