package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;

import com.zzhy.yg_ai.domain.dto.PatientRawDataTimelineGroup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface PatientService {

    List<String> listActiveReqnos();

    PatientSummaryEntity getLatestSummary(String reqno);

    /**
     * 采集患者全量信息并按天入库 patient_raw_data。
     *
     * @param reqno 患者 reqno
     * @return 执行结果 JSON
     */
    String collectAndSaveRawData(String reqno);

    /**
     * 查询待格式化结构数据（struct_data_json 为空）。
     */
    List<PatientRawDataEntity> listPendingStructRawData(String reqno);

    /**
     * 批量查询待格式化结构数据对应的 reqno（data_json 非空且 struct_data_json 为空）。
     */
    List<String> listPendingStructRawData(int limit);

    /**
     * 更新格式化后的结构化 JSON。
     */
    void saveStructDataJson(Long id, String structDataJson,String eventJson);

    void saveOrUpdateLatestSummary(PatientSummaryEntity summary);

    String getInhosdateRaw(String reqno);

    /**
     * 演示用：查询 patient_raw_data，按 reqno 分组；组内按 dataDate、id 升序。
     *
     * @param reqno 非空时仅查询该患者
     */
    List<PatientRawDataTimelineGroup> listRawDataGroupedByReqnoForDemo(String reqno);

    void saveFilterJson(Long id, String filterJson);
}
