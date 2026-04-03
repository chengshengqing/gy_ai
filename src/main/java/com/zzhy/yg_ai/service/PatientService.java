package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;

import com.zzhy.yg_ai.domain.dto.PatientRawDataTimelineGroup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface PatientService {

    List<String> listActiveReqnos();

    LocalDateTime getLatestSourceBatchTime();

    /**
     * 采集患者全量信息并按天入库 patient_raw_data。
     *
     * @param reqno 患者 reqno
     * @return 执行结果 JSON
     */
    String collectAndSaveRawData(String reqno);

    RawDataCollectResult collectAndSaveRawDataResult(String reqno);

    RawDataCollectResult collectAndSaveRawDataResult(String reqno,
                                                     LocalDateTime previousSourceLastTime,
                                                     LocalDateTime sourceBatchTime);

    PatientRawDataEntity getRawDataById(Long id);

    /**
     * 查询待格式化结构数据（struct_data_json 为空）。
     */
    List<PatientRawDataEntity> listPendingStructRawData(String reqno, LocalDate replayFromDate);

    List<PatientRawDataEntity> listPendingEventRawData(String reqno, LocalDate replayFromDate);

    void resetDerivedDataForRawData(Long rawDataId);

    /**
     * 更新格式化后的结构化 JSON。
     */
    void saveStructDataJson(Long id, String structDataJson, String eventJson);

    void saveEventJson(Long id, String eventJson);

    String buildSummaryWindowJson(String reqno, LocalDate anchorDate, int windowDays);

    String getInhosdateRaw(String reqno);

    /**
     * 演示用：查询 patient_raw_data，按 reqno 分组；组内按 dataDate、id 升序。
     *
     * @param reqno 非空时仅查询该患者
     */
    List<PatientRawDataTimelineGroup> listRawDataGroupedByReqnoForDemo(String reqno);

    void saveFilterJson(Long id, String filterJson);
}
