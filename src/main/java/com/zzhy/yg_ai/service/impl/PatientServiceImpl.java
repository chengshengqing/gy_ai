package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.dto.PatientRawDataTimelineGroup;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.mapper.PatientRawDataMapper;
import com.zzhy.yg_ai.mapper.PatientSummaryMapper;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class PatientServiceImpl implements PatientService {

    private final PatientRawDataMapper patientRawDataMapper;
    private final PatientSummaryMapper patientSummaryMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<String> listActiveReqnos() {
        return patientRawDataMapper.selectActiveReqnos();
    }

    @Override
    public PatientSummaryEntity getLatestSummary(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return null;
        }
        return patientSummaryMapper.selectOne(new QueryWrapper<PatientSummaryEntity>()
                .eq("reqno", reqno)
                .orderByDesc("update_time")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
    }

    @Override
    public String collectAndSaveRawData(String reqno) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reqno", reqno);

        if (!StringUtils.hasText(reqno)) {
            result.put("status", "failed");
            result.put("message", "reqno不能为空");
            return writeJson(result);
        }

        LocalDateTime sourceLastTime = patientRawDataMapper.selectSourceLastTime();
        LocalDateTime storedLastTime = patientRawDataMapper.selectStoredLastTimeByReqno(reqno);
        if (storedLastTime != null && (sourceLastTime.isBefore(storedLastTime) || sourceLastTime.isEqual(storedLastTime))) {
            result.put("status", "no_data");
            result.put("message", "患者信息已是最新");
            result.put("storedLastTime", storedLastTime);
            result.put("sourceLastTime", sourceLastTime);
            return writeJson(result);
        }

        PatientCourseData courseData = buildCourseData(reqno, storedLastTime, sourceLastTime);
        if (courseData.getPatInfor() == null) {
            result.put("status", "no_data");
            result.put("message", "未查询到患者信息");
            result.put("storedLastTime", storedLastTime);
            result.put("sourceLastTime", sourceLastTime);
            return writeJson(result);
        }

        Map<LocalDate, DailyPatientRawData> dailyDataMap = groupByDay(courseData);
        if (dailyDataMap.isEmpty()) {
            LocalDate fallbackDate = courseData.getPatInfor().getInhosday() == null
                    ? LocalDate.now()
                    : courseData.getPatInfor().getInhosday().toLocalDate();
            DailyPatientRawData fallbackData = new DailyPatientRawData();
            fallbackData.setReqno(reqno);
            fallbackData.setDataDate(fallbackDate);
            fallbackData.setPatInfor(courseData.getPatInfor());
            fallbackData.setOtherInfo(courseData.getOtherInfo());
            dailyDataMap.put(fallbackDate, fallbackData);
        }

        int savedCount = 0;
        LocalDateTime now = LocalDateTime.now();
        for (DailyPatientRawData dailyData : dailyDataMap.values()) {
            PatientRawDataEntity rawDataEntity = new PatientRawDataEntity();
            rawDataEntity.setReqno(reqno);
            rawDataEntity.setDataDate(dailyData.getDataDate());
            rawDataEntity.setDataJson(buildSemanticBlockJson(dailyData));
            rawDataEntity.setClinicalNotes(String.join(" ", buildClinicalNotes(dailyData.getPatIllnessCourseList())));
            rawDataEntity.setStructDataJson(null);
            rawDataEntity.setCreateTime(now);
            rawDataEntity.setLastTime(sourceLastTime);
            
            patientRawDataMapper.insert(rawDataEntity);
            savedCount++;
        }

        result.put("status", "success");
        result.put("savedDays", savedCount);
        result.put("storedLastTime", storedLastTime);
        result.put("sourceLastTime", sourceLastTime);
        return writeJson(result);
    }

    @Override
    public List<PatientRawDataEntity> listPendingStructRawData(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return Collections.emptyList();
        }
        return patientRawDataMapper.selectList(new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno)
                .isNull("struct_data_json")
                .isNull("event_json")
                .isNotNull("data_json")
                .orderByAsc("id"));
    }

    @Override
    public List<PatientRawDataEntity> listPendingStructRawData(int limit) {
        int size = limit <= 0 ? 50 : limit;
        return patientRawDataMapper.selectList(new QueryWrapper<PatientRawDataEntity>()
                .isNull("struct_data_json")
                .isNull("event_json")
                .isNotNull("data_json")
                .orderByAsc("id")
                .last("OFFSET 0 ROWS FETCH NEXT " + size + " ROWS ONLY"));
    }

    @Override
    public List<PatientRawDataEntity> listReadyStructRawData(int limit) {
        int size = limit <= 0 ? 50 : limit;
        return patientRawDataMapper.selectList(new QueryWrapper<PatientRawDataEntity>()
                .isNotNull("struct_data_json")
                .orderByAsc("id")
                .last("OFFSET 0 ROWS FETCH NEXT " + size + " ROWS ONLY"));
    }

    @Override
    public List<String> listReqnosWithUnprocessedStructData(int limit) {
        int size = limit <= 0 ? 50 : limit;
        return patientRawDataMapper.selectReqnosWithUnprocessedStructData(size);
    }

    @Override
    public List<PatientRawDataEntity> listUnprocessedStructDataByReqno(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return Collections.emptyList();
        }
        return patientRawDataMapper.selectUnprocessedStructDataByReqno(reqno);
    }

    @Override
    public void markSummaryUpdateTime(String reqno,String eventsJson, LocalDateTime lastTime) {
        if (!StringUtils.hasText(reqno) || lastTime == null) {
            return;
        }
        PatientSummaryEntity latestSummary = getLatestSummary(reqno);
        if (latestSummary == null) {
            PatientSummaryEntity summary = new PatientSummaryEntity();
            summary.setReqno(reqno);
            summary.setSummaryJson(eventsJson);
            summary.setTokenCount(eventsJson.length());
            summary.setUpdateTime(lastTime);
            patientSummaryMapper.insert(summary);
            return;
        }
        latestSummary.setUpdateTime(lastTime);
        patientSummaryMapper.updateById(latestSummary);
    }

    @Override
    public void saveStructDataJson(Long id, String structDataJson,String eventJson) {
        if (id == null) {
            return;
        }
        PatientRawDataEntity update = new PatientRawDataEntity();
        update.setId(id);
        update.setStructDataJson(structDataJson);
        update.setEventJson(eventJson);
        patientRawDataMapper.updateById(update);
    }

    @Override
    public void saveRawData(PatientRawDataEntity rawData) {
        if (rawData.getCreateTime() == null) {
            rawData.setCreateTime(LocalDateTime.now());
        }
        patientRawDataMapper.insert(rawData);
    }

    @Override
    public void saveSummary(PatientSummaryEntity summary) {
        if (summary.getUpdateTime() == null) {
            summary.setUpdateTime(LocalDateTime.now());
        }
        patientSummaryMapper.insert(summary);
    }

    @Override
    public PatientRawDataEntity getInhosdateRaw(String reqno) {
        return patientRawDataMapper.selectOne(new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno)
                .isNotNull("struct_data_json")
                .isNotNull("data_date")
                .orderByDesc("data_date")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
    }

    @Override
    public List<PatientRawDataTimelineGroup> listRawDataGroupedByReqnoForDemo(String reqno) {
        QueryWrapper<PatientRawDataEntity> qw = new QueryWrapper<>();
        if (StringUtils.hasText(reqno)) {
            qw.eq("reqno", reqno);
        }
        qw.orderByAsc("reqno", "data_date", "id");
        List<PatientRawDataEntity> all = patientRawDataMapper.selectList(qw);
        Map<String, List<PatientRawDataEntity>> grouped = new LinkedHashMap<>();
        for (PatientRawDataEntity row : all) {
            String key = StringUtils.hasText(row.getReqno()) ? row.getReqno() : "";
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<PatientRawDataTimelineGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<PatientRawDataEntity>> e : grouped.entrySet()) {
            result.add(new PatientRawDataTimelineGroup(e.getKey(), e.getValue()));
        }
        return result;
    }

    private PatientCourseData buildCourseData(String reqno, LocalDateTime lastTime, LocalDateTime sourceLastTime) {
        PatientCourseData data = new PatientCourseData();
        data.setPatInfor(patientRawDataMapper.selectPatInfor(reqno));
        data.setPatDiagInforList(patientRawDataMapper.selectDiagByReqno(reqno, lastTime));
        data.setPatBodySurfaceList(patientRawDataMapper.selectBodySurfaceByReqno(reqno, lastTime));
        data.setLongDoctorAdviceList(patientRawDataMapper.selectLongDoctorAdviceByReqno(reqno, lastTime));
        data.setTemporaryDoctorAdviceList(patientRawDataMapper.selectTemporaryDoctorAdviceByReqno(reqno, lastTime));
        data.setSgDoctorAdviceList(patientRawDataMapper.selectSgDoctorAdviceByReqno(reqno, lastTime));
        data.setPatIllnessCourseList(patientRawDataMapper.selectIllnessCourseByReqno(reqno, lastTime));

        List<PatientCourseData.PatTestSam> testSamList = patientRawDataMapper.selectTestSamByReqno(reqno, lastTime);
        for (PatientCourseData.PatTestSam testSam : nonNullList(testSamList)) {
            testSam.setResultList(patientRawDataMapper.selectTestResultBySamreqno(reqno, testSam.getSamreqno(), lastTime));
        }
        data.setPatTestSamList(testSamList);

        data.setPatUseMedicineList(patientRawDataMapper.selectUseMedicineByReqno(reqno, lastTime));
        data.setPatVideoResultList(patientRawDataMapper.selectVideoResultByReqno(reqno, lastTime));
        data.setPatTransferList(patientRawDataMapper.selectTransferByReqno(reqno, lastTime));

        List<PatientCourseData.PatOpsCutInfor> opsList = patientRawDataMapper.selectOpsByReqno(reqno, lastTime);
        for (PatientCourseData.PatOpsCutInfor ops : nonNullList(opsList)) {
            ops.setPreWardMedicineList(patientRawDataMapper.selectPreWardOpsMedicine(reqno, ops.getOpsId(), lastTime));
            ops.setPerioperativeMedicineList(patientRawDataMapper.selectPerioperativeOpsMedicine(reqno, ops.getOpsId(), lastTime));
        }
        data.setPatOpsCutInforList(opsList);

        List<PatientCourseData.PatTest> patTestList = patientRawDataMapper.selectPatTestByReqno(reqno, lastTime);
        for (PatientCourseData.PatTest patTest : nonNullList(patTestList)) {
            List<PatientCourseData.MicrobeInfo> microbeList =
                    patientRawDataMapper.selectMicrobeBySamreqno(reqno, patTest.getSamreqno(), lastTime);
            for (PatientCourseData.MicrobeInfo microbeInfo : nonNullList(microbeList)) {
                microbeInfo.setAntiDrugList(
                        patientRawDataMapper.selectAntiDrugByMicrobe(reqno, microbeInfo.getSamreqno(), microbeInfo.getDataCode(), lastTime));
            }
            patTest.setMicrobeList(microbeList);
        }
        data.setPatTestList(patTestList);

        PatientCourseData.OtherInfo otherInfo = new PatientCourseData.OtherInfo();
        otherInfo.setQueryTime(LocalDateTime.now());
        otherInfo.setSourceLastTime(sourceLastTime);
        otherInfo.setDataStartTime(lastTime);
        data.setOtherInfo(otherInfo);
        return data;
    }

    private Map<LocalDate, DailyPatientRawData> groupByDay(PatientCourseData data) {
        Map<LocalDate, DailyPatientRawData> dayDataMap = new TreeMap<>();
        PatientCourseData.PatInfor patInfor = data.getPatInfor();
        String reqno = patInfor == null ? null : patInfor.getReqno();

        for (PatientCourseData.PatDiagInfor item : nonNullList(data.getPatDiagInforList())) {
            addToDayMap(dayDataMap, reqno, data, item.getDiagTime()).getPatDiagInforList().add(item);
        }
        for (PatientCourseData.PatBodySurface item : nonNullList(data.getPatBodySurfaceList())) {
            addToDayMap(dayDataMap, reqno, data, item.getMeasuredate()).getPatBodySurfaceList().add(item);
        }
        for (PatientCourseData.PatDoctorAdvice item : nonNullList(data.getLongDoctorAdviceList())) {
            addToDayMap(dayDataMap, reqno, data, item.getBegtime()).getLongDoctorAdviceList().add(item);
        }
        for (PatientCourseData.PatDoctorAdvice item : nonNullList(data.getTemporaryDoctorAdviceList())) {
            addToDayMap(dayDataMap, reqno, data, item.getBegtime()).getTemporaryDoctorAdviceList().add(item);
        }
        for (PatientCourseData.PatDoctorAdvice item : nonNullList(data.getSgDoctorAdviceList())) {
            addToDayMap(dayDataMap, reqno, data, item.getBegtime()).getSgDoctorAdviceList().add(item);
        }
        for (PatientCourseData.PatIllnessCourse item : nonNullList(data.getPatIllnessCourseList())) {
            addToDayMap(dayDataMap, reqno, data, item.getCreattime()).getPatIllnessCourseList().add(item);
        }
        for (PatientCourseData.PatTestSam item : nonNullList(data.getPatTestSamList())) {
            addToDayMap(dayDataMap, reqno, data, item.getSendtestdate()).getPatTestSamList().add(item);
        }
        for (PatientCourseData.PatUseMedicine item : nonNullList(data.getPatUseMedicineList())) {
            addToDayMap(dayDataMap, reqno, data, item.getBeginTime()).getPatUseMedicineList().add(item);
        }
        for (PatientCourseData.PatVideoResult item : nonNullList(data.getPatVideoResultList())) {
            addToDayMap(dayDataMap, reqno, data, item.getReporttime()).getPatVideoResultList().add(item);
        }
        for (PatientCourseData.PatTransfer item : nonNullList(data.getPatTransferList())) {
            addToDayMap(dayDataMap, reqno, data, item.getIndeptdate()).getPatTransferList().add(item);
        }
        for (PatientCourseData.PatOpsCutInfor item : nonNullList(data.getPatOpsCutInforList())) {
            addToDayMap(dayDataMap, reqno, data, item.getBegTime()).getPatOpsCutInforList().add(item);
        }
        for (PatientCourseData.PatTest item : nonNullList(data.getPatTestList())) {
            addToDayMap(dayDataMap, reqno, data, item.getSampletime()).getPatTestList().add(item);
        }
        return dayDataMap;
    }

    private DailyPatientRawData addToDayMap(Map<LocalDate, DailyPatientRawData> dayDataMap,
                                            String reqno,
                                            PatientCourseData source,
                                            LocalDateTime time) {
        LocalDate date = (time == null ? LocalDate.now() : time.toLocalDate());
        return dayDataMap.computeIfAbsent(date, key -> {
            DailyPatientRawData dayData = new DailyPatientRawData();
            dayData.setReqno(reqno);
            dayData.setDataDate(key);
            dayData.setPatInfor(source.getPatInfor());
            dayData.setOtherInfo(source.getOtherInfo());
            dayData.setPatDiagInforList(new ArrayList<>());
            dayData.setPatBodySurfaceList(new ArrayList<>());
            dayData.setLongDoctorAdviceList(new ArrayList<>());
            dayData.setTemporaryDoctorAdviceList(new ArrayList<>());
            dayData.setSgDoctorAdviceList(new ArrayList<>());
            dayData.setPatIllnessCourseList(new ArrayList<>());
            dayData.setPatTestSamList(new ArrayList<>());
            dayData.setPatUseMedicineList(new ArrayList<>());
            dayData.setPatVideoResultList(new ArrayList<>());
            dayData.setPatTransferList(new ArrayList<>());
            dayData.setPatOpsCutInforList(new ArrayList<>());
            dayData.setPatTestList(new ArrayList<>());
            return dayData;
        });
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON序列化失败", e);
            return "{\"status\":\"failed\",\"message\":\"JSON序列化失败\"}";
        }
    }

    private String buildSemanticBlockJson(DailyPatientRawData dailyData) {
        try {
            Map<String, Object> semantic = new LinkedHashMap<>();
            semantic.put("reqno", dailyData.getReqno());
            semantic.put("dataDate", dailyData.getDataDate() == null ? null : dailyData.getDataDate().toString());
            semantic.put("patient_info", buildPatientInfo(dailyData.getPatInfor()));
            semantic.put("diagnosis", buildDiagnosis(dailyData.getPatDiagInforList()));
            semantic.put("vital_signs", buildVitalSigns(dailyData.getPatBodySurfaceList()));
            semantic.put("lab_results", buildLabResults(dailyData.getPatTestSamList(), dailyData.getPatTestList()));
            semantic.put("imaging", buildImaging(dailyData.getPatVideoResultList()));
            semantic.put("doctor_orders", buildDoctorOrders(dailyData.getLongDoctorAdviceList(), dailyData.getTemporaryDoctorAdviceList(), dailyData.getSgDoctorAdviceList()));
            semantic.put("clinical_notes", buildClinicalNotes(dailyData.getPatIllnessCourseList()));
            semantic.put("pat_illnessCourse", buildPatIllnessCourseText(dailyData.getPatIllnessCourseList()));
            return objectMapper.writeValueAsString(semantic);
        } catch (Exception e) {
            log.error("语义块JSON生成失败，回退普通序列化", e);
            return writeJson(dailyData);
        }
    }

    private Map<String, Object> buildPatientInfo(PatientCourseData.PatInfor patInfor) {
        Map<String, Object> patientInfo = new LinkedHashMap<>();
        if (patInfor == null) {
            return patientInfo;
        }
        patientInfo.put("sex", patInfor.getSex());
        patientInfo.put("age", parseAgeNumber(patInfor.getAge()));
        patientInfo.put("admission_time", formatDateTime(patInfor.getInhosday(), "yyyy-MM-dd HH:mm"));
        patientInfo.put("department", patInfor.getDisname());
        return patientInfo;
    }

    private List<String> buildDiagnosis(List<PatientCourseData.PatDiagInfor> diagList) {
        List<String> diagnosis = new ArrayList<>();
        for (PatientCourseData.PatDiagInfor diag : nonNullList(diagList)) {
            if (StringUtils.hasText(diag.getDiagName()) && !diagnosis.contains(diag.getDiagName())) {
                diagnosis.add(diag.getDiagName());
            }
        }
        return diagnosis;
    }

    private List<Map<String, Object>> buildVitalSigns(List<PatientCourseData.PatBodySurface> bodySurfaceList) {
        List<Map<String, Object>> vitalSigns = new ArrayList<>();
        for (PatientCourseData.PatBodySurface bodySurface : nonNullList(bodySurfaceList)) {
            if (!hasAnyAbnormalVitalSign(bodySurface)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", formatDateTime(bodySurface.getMeasuredate(), "HH:mm"));
            if (StringUtils.hasText(bodySurface.getTemperature())) {
                item.put("temperature", bodySurface.getTemperature());
            }
            if (StringUtils.hasText(bodySurface.getStoolCount())) {
                item.put("stool_count", bodySurface.getStoolCount());
            }
            if (StringUtils.hasText(bodySurface.getPulse())) {
                item.put("pulse", bodySurface.getPulse());
            }
            if (StringUtils.hasText(bodySurface.getBreath())) {
                item.put("respiration", bodySurface.getBreath());
            }
            if (StringUtils.hasText(bodySurface.getBloodPressure())) {
                item.put("blood_pressure", bodySurface.getBloodPressure());
            }
            vitalSigns.add(item);
        }
        return vitalSigns;
    }

    private Map<String, Object> buildLabResults(List<PatientCourseData.PatTestSam> testSamList,
                                                List<PatientCourseData.PatTest> patTestList) {
        Map<String, Object> labResults = new LinkedHashMap<>();
        List<Map<String, Object>> testPanels = new ArrayList<>();
        for (PatientCourseData.PatTestSam sam : nonNullList(testSamList)) {
            List<Map<String, Object>> results = new ArrayList<>();
            boolean hasAbnormal = false;

            for (PatientCourseData.PatTestResult result : nonNullList(sam.getResultList())) {
                String abnormalFlag = result.getAllJyFlag();
                // 只有当 abnormal_flag 为“异常”时，才添加到 results 列表
                if ("异常".equals(abnormalFlag)) {
                    Map<String, Object> resultItem = new LinkedHashMap<>();
                    resultItem.put("item_name", result.getItemname());
                    resultItem.put("eng_name", result.getEngname());
                    resultItem.put("result", result.getResultdesc());
                    resultItem.put("state", result.getState());
                    resultItem.put("unit_or_range", result.getUnit());
                    resultItem.put("ref_desc", result.getRefdesc());
                    resultItem.put("abnormal_flag", abnormalFlag);
                    resultItem.put("display", buildLabResultValue(result));
                    results.add(resultItem);
                    hasAbnormal = true;
                }
            }

            // 只有当 results 中至少有一个“异常”记录时，才将该 panel 添加到 testPanels
            if (hasAbnormal) {
                Map<String, Object> panel = new LinkedHashMap<>();
                panel.put("samreqno", sam.getSamreqno());
                panel.put("test_aim", sam.getTestaim());
                panel.put("sample_type", sam.getDataName());
                panel.put("send_test_time", formatDateTime(sam.getSendtestdate(), "yyyy-MM-dd HH:mm"));
                panel.put("test_time", formatDateTime(sam.getTestdate(), "yyyy-MM-dd HH:mm"));
                panel.put("results", results);
                testPanels.add(panel);
            }
        }
        labResults.put("test_panels", testPanels);

        List<Map<String, Object>> microbePanels = new ArrayList<>();
        for (PatientCourseData.PatTest patTest : nonNullList(patTestList)) {
            List<Map<String, Object>> microbeItems = new ArrayList<>();
            for (PatientCourseData.MicrobeInfo microbeInfo : nonNullList(patTest.getMicrobeList())) {
                List<String> drugSensitivityList = new ArrayList<>();
                boolean hasSensitiveDrug = false;

                for (PatientCourseData.AntiDrugInfo antiDrug : nonNullList(microbeInfo.getAntiDrugList())) {
                    String sensitivity = antiDrug.getSensitivity();
                    // 只有当 sensitivity 为"耐药"或"中介"时才处理
                    if ("耐药".equals(sensitivity) || "中介".equals(sensitivity)) {
                        hasSensitiveDrug = true;
                        StringBuilder sb = new StringBuilder();
                        if (StringUtils.hasText(antiDrug.getDataName())) {
                            sb.append(antiDrug.getDataName());
                        }
                        if (StringUtils.hasText(sensitivity)) {
                            if (sb.length() > 0) {
                                sb.append(" ");
                            }
                            sb.append(sensitivity);
                        }
                        if (StringUtils.hasText(antiDrug.getMic())) {
                            if (sb.length() > 0) {
                                sb.append(" ");
                            }
                            sb.append(antiDrug.getMic());
                        }
                        if (sb.length() > 0) {
                            drugSensitivityList.add(sb.toString());
                        }
                    }
                }

                // 只有当存在"耐药"或"中介"的药物时，才添加该微生物项
                if (hasSensitiveDrug) {
                    Map<String, Object> microbeItem = new LinkedHashMap<>();
                    microbeItem.put("data_code", microbeInfo.getDataCode());
                    microbeItem.put("result", microbeInfo.getResult());
                    microbeItem.put("result1", microbeInfo.getResult1());
                    microbeItem.put("result2", microbeInfo.getResult2());
                    microbeItem.put("execute_time", formatDateTime(microbeInfo.getExedate(), "yyyy-MM-dd HH:mm"));
                    microbeItem.put("drug_sensitivity", drugSensitivityList);
                    microbeItems.add(microbeItem);
                }
            }

            // 只有当存在包含"耐药"或"中介"药物的微生物项时，才添加该面板
            if (!microbeItems.isEmpty()) {
                Map<String, Object> microbePanel = new LinkedHashMap<>();
                microbePanel.put("samreqno", patTest.getSamreqno());
                microbePanel.put("test_object", patTest.getTestobject());
                microbePanel.put("sample_type", patTest.getDataName());
                microbePanel.put("sample_time", formatDateTime(patTest.getSampletime(), "yyyy-MM-dd HH:mm"));
                microbePanel.put("microbe_items", microbeItems);
                microbePanels.add(microbePanel);
            }
        }
        labResults.put("microbe_panels", microbePanels);

        return labResults;
    }

    private List<Map<String, Object>> buildImaging(List<PatientCourseData.PatVideoResult> videoResultList) {
        List<Map<String, Object>> imagingList = new ArrayList<>();
        for (PatientCourseData.PatVideoResult videoResult : nonNullList(videoResultList)) {
            Map<String, Object> imaging = new LinkedHashMap<>();
            imaging.put("type", videoResult.getNames());
//            imaging.put("type", inferImagingType(videoResult.getNames()));
            imaging.put("result", splitImagingResult(videoResult.getTestresult()));
            imagingList.add(imaging);
        }
        return imagingList;
    }

    private Map<String, Object> buildDoctorOrders(List<PatientCourseData.PatDoctorAdvice> longDoctorAdviceList,
                                                  List<PatientCourseData.PatDoctorAdvice> temporaryDoctorAdviceList,
                                                  List<PatientCourseData.PatDoctorAdvice> sgDoctorAdviceList) {
        Map<String, Object> doctorOrders = new LinkedHashMap<>();
        doctorOrders.put("long_term", extractOrderNameList(longDoctorAdviceList));
        doctorOrders.put("temporary", extractOrderNameList(temporaryDoctorAdviceList));
        doctorOrders.put("sg", extractOrderNameList(sgDoctorAdviceList));
        return doctorOrders;
    }

    private List<String> buildClinicalNotes(List<PatientCourseData.PatIllnessCourse> illnessCourseList) {
        List<String> clinicalNotes = new ArrayList<>();
        for (PatientCourseData.PatIllnessCourse course : nonNullList(illnessCourseList)) {
            if (StringUtils.hasText(course.getItemname()) && !clinicalNotes.contains(course.getItemname())) {
                clinicalNotes.add(course.getItemname());
            }
        }
        return clinicalNotes;
    }

    private List<String> buildPatIllnessCourseText(List<PatientCourseData.PatIllnessCourse> illnessCourseList) {
        List<String> illnessCourseTextList = new ArrayList<>();
        for (PatientCourseData.PatIllnessCourse course : nonNullList(illnessCourseList)) {
            if (!StringUtils.hasText(course.getIllnesscontent())) {
                continue;
            }
            illnessCourseTextList.add(course.getIllnesscontent());
        }
        return illnessCourseTextList;
    }

    private Integer parseAgeNumber(String ageText) {
        if (!StringUtils.hasText(ageText)) {
            return null;
        }
        String digits = ageText.replaceAll("[^0-9]", "");
        if (!StringUtils.hasText(digits)) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatDateTime(LocalDateTime time, String pattern) {
        if (time == null) {
            return null;
        }
        return time.format(DateTimeFormatter.ofPattern(pattern));
    }

    private boolean isAbnormalTemperature(String tempText) {
        if (!StringUtils.hasText(tempText)) {
            return false;
        }
        try {
            double temp = Double.parseDouble(tempText);
            return temp >= 37.3d || temp < 36.0d;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean hasAnyAbnormalVitalSign(PatientCourseData.PatBodySurface bodySurface) {
        return isAbnormalTemperature(bodySurface.getTemperature())
                || isAbnormalStoolCount(bodySurface.getStoolCount())
                || isAbnormalPulse(bodySurface.getPulse())
                || isAbnormalRespiration(bodySurface.getBreath())
                || isAbnormalBloodPressure(bodySurface.getBloodPressure());
    }

    private boolean isAbnormalStoolCount(String stoolCountText) {
        if (!StringUtils.hasText(stoolCountText)) {
            return false;
        }
        try {
            double stoolCount = Double.parseDouble(stoolCountText);
            return stoolCount > 3d;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isAbnormalPulse(String pulseText) {
        if (!StringUtils.hasText(pulseText)) {
            return false;
        }
        try {
            double pulse = Double.parseDouble(pulseText);
            return pulse < 60d || pulse > 100d;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isAbnormalRespiration(String respirationText) {
        if (!StringUtils.hasText(respirationText)) {
            return false;
        }
        try {
            double respiration = Double.parseDouble(respirationText);
            return respiration < 12d || respiration > 20d;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isAbnormalBloodPressure(String bloodPressureText) {
        if (!StringUtils.hasText(bloodPressureText)) {
            return false;
        }
        String[] values = bloodPressureText.split("/");
        if (values.length != 2) {
            return false;
        }
        try {
            double systolic = Double.parseDouble(values[0].trim());
            double diastolic = Double.parseDouble(values[1].trim());
            return systolic >= 140d || systolic < 90d || diastolic >= 90d || diastolic < 60d;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildLabResultValue(PatientCourseData.PatTestResult result) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(result.getResultdesc())) {
            sb.append(result.getResultdesc());
        }
        if (StringUtils.hasText(result.getState())) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(result.getState());
        } else if (StringUtils.hasText(result.getAllJyFlag())) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append("(").append(result.getAllJyFlag()).append(")");
        }
        return sb.toString();
    }

    private List<String> splitImagingResult(String testResult) {
        List<String> results = new ArrayList<>();
        if (!StringUtils.hasText(testResult)) {
            return results;
        }
        String normalized = testResult.replace("\r", "\n");
        String[] parts = normalized.split("[\\n；;。]");
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                results.add(part.trim());
            }
        }
        if (results.isEmpty()) {
            results.add(testResult.trim());
        }
        return results;
    }

    private List<String> extractOrderNameList(List<PatientCourseData.PatDoctorAdvice> adviceList) {
        List<String> orderNames = new ArrayList<>();
        for (PatientCourseData.PatDoctorAdvice advice : nonNullList(adviceList)) {
            if (StringUtils.hasText(advice.getDocadvice()) && !orderNames.contains(advice.getDocadvice())) {
                orderNames.add(advice.getDocadvice());
            }
        }
        return orderNames;
    }

    private <T> List<T> nonNullList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static class DailyPatientRawData {
        private String reqno;
        private LocalDate dataDate;
        private PatientCourseData.PatInfor patInfor;
        private List<PatientCourseData.PatDiagInfor> patDiagInforList;
        private List<PatientCourseData.PatBodySurface> patBodySurfaceList;
        private List<PatientCourseData.PatDoctorAdvice> longDoctorAdviceList;
        private List<PatientCourseData.PatDoctorAdvice> temporaryDoctorAdviceList;
        private List<PatientCourseData.PatDoctorAdvice> sgDoctorAdviceList;
        private List<PatientCourseData.PatIllnessCourse> patIllnessCourseList;
        private List<PatientCourseData.PatTestSam> patTestSamList;
        private List<PatientCourseData.PatUseMedicine> patUseMedicineList;
        private List<PatientCourseData.PatVideoResult> patVideoResultList;
        private List<PatientCourseData.PatTransfer> patTransferList;
        private List<PatientCourseData.PatOpsCutInfor> patOpsCutInforList;
        private List<PatientCourseData.PatTest> patTestList;
        private PatientCourseData.OtherInfo otherInfo;

        public String getReqno() {
            return reqno;
        }

        public void setReqno(String reqno) {
            this.reqno = reqno;
        }

        public LocalDate getDataDate() {
            return dataDate;
        }

        public void setDataDate(LocalDate dataDate) {
            this.dataDate = dataDate;
        }

        public PatientCourseData.PatInfor getPatInfor() {
            return patInfor;
        }

        public void setPatInfor(PatientCourseData.PatInfor patInfor) {
            this.patInfor = patInfor;
        }

        public List<PatientCourseData.PatDiagInfor> getPatDiagInforList() {
            return patDiagInforList;
        }

        public void setPatDiagInforList(List<PatientCourseData.PatDiagInfor> patDiagInforList) {
            this.patDiagInforList = patDiagInforList;
        }

        public List<PatientCourseData.PatBodySurface> getPatBodySurfaceList() {
            return patBodySurfaceList;
        }

        public void setPatBodySurfaceList(List<PatientCourseData.PatBodySurface> patBodySurfaceList) {
            this.patBodySurfaceList = patBodySurfaceList;
        }

        public List<PatientCourseData.PatDoctorAdvice> getLongDoctorAdviceList() {
            return longDoctorAdviceList;
        }

        public void setLongDoctorAdviceList(List<PatientCourseData.PatDoctorAdvice> longDoctorAdviceList) {
            this.longDoctorAdviceList = longDoctorAdviceList;
        }

        public List<PatientCourseData.PatDoctorAdvice> getTemporaryDoctorAdviceList() {
            return temporaryDoctorAdviceList;
        }

        public void setTemporaryDoctorAdviceList(List<PatientCourseData.PatDoctorAdvice> temporaryDoctorAdviceList) {
            this.temporaryDoctorAdviceList = temporaryDoctorAdviceList;
        }

        public List<PatientCourseData.PatDoctorAdvice> getSgDoctorAdviceList() {
            return sgDoctorAdviceList;
        }

        public void setSgDoctorAdviceList(List<PatientCourseData.PatDoctorAdvice> sgDoctorAdviceList) {
            this.sgDoctorAdviceList = sgDoctorAdviceList;
        }

        public List<PatientCourseData.PatIllnessCourse> getPatIllnessCourseList() {
            return patIllnessCourseList;
        }

        public void setPatIllnessCourseList(List<PatientCourseData.PatIllnessCourse> patIllnessCourseList) {
            this.patIllnessCourseList = patIllnessCourseList;
        }

        public List<PatientCourseData.PatTestSam> getPatTestSamList() {
            return patTestSamList;
        }

        public void setPatTestSamList(List<PatientCourseData.PatTestSam> patTestSamList) {
            this.patTestSamList = patTestSamList;
        }

        public List<PatientCourseData.PatUseMedicine> getPatUseMedicineList() {
            return patUseMedicineList;
        }

        public void setPatUseMedicineList(List<PatientCourseData.PatUseMedicine> patUseMedicineList) {
            this.patUseMedicineList = patUseMedicineList;
        }

        public List<PatientCourseData.PatVideoResult> getPatVideoResultList() {
            return patVideoResultList;
        }

        public void setPatVideoResultList(List<PatientCourseData.PatVideoResult> patVideoResultList) {
            this.patVideoResultList = patVideoResultList;
        }

        public List<PatientCourseData.PatTransfer> getPatTransferList() {
            return patTransferList;
        }

        public void setPatTransferList(List<PatientCourseData.PatTransfer> patTransferList) {
            this.patTransferList = patTransferList;
        }

        public List<PatientCourseData.PatOpsCutInfor> getPatOpsCutInforList() {
            return patOpsCutInforList;
        }

        public void setPatOpsCutInforList(List<PatientCourseData.PatOpsCutInfor> patOpsCutInforList) {
            this.patOpsCutInforList = patOpsCutInforList;
        }

        public List<PatientCourseData.PatTest> getPatTestList() {
            return patTestList;
        }

        public void setPatTestList(List<PatientCourseData.PatTest> patTestList) {
            this.patTestList = patTestList;
        }

        public PatientCourseData.OtherInfo getOtherInfo() {
            return otherInfo;
        }

        public void setOtherInfo(PatientCourseData.OtherInfo otherInfo) {
            this.otherInfo = otherInfo;
        }
    }
}