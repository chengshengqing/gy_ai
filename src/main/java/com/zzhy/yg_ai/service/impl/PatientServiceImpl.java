package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.agent.AgentUtils;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.common.FilterTextUtils;
import com.zzhy.yg_ai.domain.dto.PatientRawDataTimelineGroup;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventTriggerReasonCode;
import com.zzhy.yg_ai.domain.enums.PatientCourseDataType;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.mapper.PatientRawDataMapper;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import com.zzhy.yg_ai.service.PatientService;
import com.zzhy.yg_ai.service.SummaryContextCacheService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class PatientServiceImpl implements PatientService {

    private final PatientRawDataMapper patientRawDataMapper;
    private final ObjectMapper objectMapper;
    private final FilterTextUtils filterTextUtils;
    private final InfectionEventTaskService infectionEventTaskService;
    private final PatientRawDataChangeTaskService patientRawDataChangeTaskService;
    private final SummaryContextCacheService summaryContextCacheService;

    @Override
    public List<String> listActiveReqnos(LocalDateTime sinceTime,
                                         int recentAdmissionDays,
                                         int offset,
                                         int limit) {
        int safeRecentAdmissionDays = recentAdmissionDays <= 0 ? 30 : recentAdmissionDays;
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit <= 0 ? 200 : limit;
        return normalizeReqnos(
                patientRawDataMapper.selectActiveReqnos(sinceTime, safeRecentAdmissionDays, safeOffset, safeLimit)
        );
    }

    @Override
    public LocalDateTime getLatestSourceBatchTime() {
        return patientRawDataMapper.selectSourceLastTime();
    }

    @Override
    public RawDataCollectResult collectAndSaveRawDataResult(String reqno,
                                                            LocalDateTime previousSourceLastTime,
                                                            LocalDateTime sourceBatchTime) {
        RawDataCollectResult result = new RawDataCollectResult();
        result.setReqno(reqno);
        if (!StringUtils.hasText(reqno)) {
            result.setStatus("failed");
            result.setMessage("reqno不能为空");
            return result;
        }

        boolean isNewPatient = !hasPatientRawData(reqno);
        if (sourceBatchTime == null) {
            result.setStatus("failed");
            result.setMessage("采集任务缺少 sourceBatchTime");
            return result;
        }
        if (!isNewPatient && previousSourceLastTime == null) {
            result.setStatus("failed");
            result.setMessage("增量采集任务缺少 previousSourceLastTime");
            return result;
        }

        LocalDateTime effectiveSourceBatchTime = sourceBatchTime;
        EnumSet<PatientCourseDataType> changedTypes = isNewPatient
                ? PatientCourseDataType.fullSnapshot()
                : detectChangedDataTypes(reqno, previousSourceLastTime);
        result.setChangeTypes(PatientCourseDataType.toCsv(changedTypes));

        int savedCount = 0;
        List<PatientRawDataChangeTaskEntity> changedTasks = new ArrayList<>();
        LocalDateTime now = DateTimeUtils.now();
        if (!isNewPatient && changedTypes.isEmpty()) {
            result.setStatus("no_data");
            result.setMessage("当前无增量更新");
            return result;
        }

        EnumSet<PatientCourseDataType> requestedTypes = isNewPatient
                ? PatientCourseDataType.fullSnapshot()
                : EnumSet.copyOf(changedTypes);
        PatientCourseData queriedCourseData = buildCourseData(
                reqno,
                isNewPatient ? null : previousSourceLastTime,
                effectiveSourceBatchTime,
                requestedTypes
        );
        if (queriedCourseData.getPatInfor() == null) {
            result.setStatus("no_data");
            result.setMessage("未查询到患者信息");
            return result;
        }

        String firstCourse = resolveFirstIllnessCourse(reqno, queriedCourseData.getPatIllnessCourseList());
        Map<LocalDate, DailyPatientRawData> queriedDayDataMap = groupByDay(queriedCourseData);

        if (isNewPatient) {
            if (queriedDayDataMap.isEmpty()) {
                LocalDate fallbackDate = queriedCourseData.getPatInfor().getInhosday() == null
                        ? DateTimeUtils.today()
                        : queriedCourseData.getPatInfor().getInhosday().toLocalDate();
                DailyPatientRawData fallbackData = new DailyPatientRawData();
                fallbackData.setReqno(reqno);
                fallbackData.setDataDate(fallbackDate);
                fallbackData.setPatInfor(queriedCourseData.getPatInfor());
                fallbackData.setOtherInfo(queriedCourseData.getOtherInfo());
                queriedDayDataMap.put(fallbackDate, fallbackData);
            }
            for (DailyPatientRawData dailyData : queriedDayDataMap.values()) {
                PatientRawDataEntity savedRow = insertPatientRawData(reqno, dailyData, now, firstCourse);
                enqueueDownstreamTasks(changedTasks, savedRow, requestedTypes, dailyData, effectiveSourceBatchTime);
                savedCount++;
            }
        } else {
            if (queriedDayDataMap.isEmpty()) {
                result.setStatus("no_data");
                result.setMessage("当前无增量更新");
                return result;
            }

            PatientCourseData fullCourseData = null;
            Map<LocalDate, DailyPatientRawData> fullDailyDataMap = new TreeMap<>();
            for (Map.Entry<LocalDate, DailyPatientRawData> entry : queriedDayDataMap.entrySet()) {
                LocalDate changedDay = entry.getKey();
                DailyPatientRawData changedDailyData = entry.getValue();
                PatientRawDataEntity existing = findPatientRawDataByReqnoAndDate(reqno, changedDay);
                if (existing == null || !hasRequiredRawSections(existing.getDataJson(), changedTypes)) {
                    if (fullCourseData == null) {
                        fullCourseData = buildCourseData(reqno, null, effectiveSourceBatchTime, PatientCourseDataType.fullSnapshot());
                        fullDailyDataMap.putAll(groupByDay(fullCourseData));
                    }
                    DailyPatientRawData fullDailyData = fullDailyDataMap.get(changedDay);
                    if (fullDailyData == null) {
                        continue;
                    }
                    PatientRawDataEntity savedRow = upsertPatientRawData(reqno, fullDailyData, now, firstCourse);
                    enqueueDownstreamTasks(
                            changedTasks,
                            savedRow,
                            PatientCourseDataType.fullSnapshot(),
                            fullDailyData,
                            effectiveSourceBatchTime
                    );
                } else {
                    PatientRawDataEntity savedRow = mergeChangedRawData(existing, changedDailyData, changedTypes, now, firstCourse);
                    enqueueDownstreamTasks(
                            changedTasks,
                            savedRow,
                            changedTypes,
                            changedDailyData,
                            effectiveSourceBatchTime
                    );
                }
                savedCount++;
            }
        }

        result.setStatus("success");
        result.setSavedDays(savedCount);
        result.setMessage("患者语义块采集完成");
        if (savedCount > 0) {
            patientRawDataChangeTaskService.appendChanges(changedTasks);
        }
        return result;
    }

    @Override
    public PatientRawDataEntity getRawDataById(Long id) {
        if (id == null) {
            return null;
        }
        return patientRawDataMapper.selectById(id);
    }

    @Override
    public PatientRawDataEntity getFirstRawDataByReqno(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return null;
        }
        return patientRawDataMapper.selectOne(new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno.trim())
                .orderByAsc("data_date", "id")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
    }

    @Override
    public PatientRawDataEntity getRawDataByReqnoAndDate(String reqno, LocalDate dataDate) {
        if (!StringUtils.hasText(reqno) || dataDate == null) {
            return null;
        }
        return findPatientRawDataByReqnoAndDate(reqno.trim(), dataDate);
    }

    @Override
    public List<PatientRawDataEntity> listPendingStructRawData(String reqno, LocalDate replayFromDate) {
        if (!StringUtils.hasText(reqno)) {
            return Collections.emptyList();
        }
        QueryWrapper<PatientRawDataEntity> queryWrapper = new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno)
                .isNull("struct_data_json")
                .isNotNull("data_json")
                .orderByAsc("data_date", "id");
        if (replayFromDate != null) {
            queryWrapper.ge("data_date", replayFromDate);
        }
        return patientRawDataMapper.selectList(queryWrapper);
    }

    @Override
    public List<PatientRawDataEntity> listPendingEventRawData(String reqno, LocalDate replayFromDate) {
        if (!StringUtils.hasText(reqno)) {
            return Collections.emptyList();
        }
        QueryWrapper<PatientRawDataEntity> queryWrapper = new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno)
                .isNotNull("struct_data_json")
                .isNotNull("data_json")
                .orderByAsc("data_date", "id");
        if (replayFromDate != null) {
            queryWrapper.ge("data_date", replayFromDate);
        }
        return patientRawDataMapper.selectList(queryWrapper);
    }

    @Override
    public void saveStructDataJson(Long id, String structDataJson, String eventJson) {
        if (id == null) {
            return;
        }
        PatientRawDataEntity existing = patientRawDataMapper.selectById(id);
        PatientRawDataEntity update = new PatientRawDataEntity();
        update.setId(id);
        update.setStructDataJson(structDataJson);
        update.setEventJson(eventJson);
        patientRawDataMapper.updateById(update);
        if (existing != null && StringUtils.hasText(existing.getReqno()) && existing.getDataDate() != null) {
            summaryContextCacheService.refreshEventExtractorContextDay(existing.getReqno(), existing.getDataDate(), eventJson);
        }
    }

    @Override
    public void saveEventJson(Long id, String eventJson) {
        if (id == null) {
            return;
        }
        PatientRawDataEntity existing = patientRawDataMapper.selectById(id);
        PatientRawDataEntity update = new PatientRawDataEntity();
        update.setId(id);
        update.setEventJson(eventJson);
        patientRawDataMapper.updateById(update);
        if (existing != null && StringUtils.hasText(existing.getReqno()) && existing.getDataDate() != null) {
            summaryContextCacheService.refreshEventExtractorContextDay(existing.getReqno(), existing.getDataDate(), eventJson);
        }
    }

    @Override
    public void saveFilterJson(Long id, String filterJson) {
        if (id == null) {
            return;
        }
        PatientRawDataEntity update = new PatientRawDataEntity();
        update.setId(id);
        update.setFilterDataJson(filterJson);
        patientRawDataMapper.updateById(update);
    }

    @Override
    public String buildSummaryWindowJson(String reqno, LocalDate anchorDate) {
        return summaryContextCacheService.getOrBuildEventExtractorContext(reqno, anchorDate);
    }

    @Override
    public String getInhosdateRaw(String reqno) {
        PatientRawDataEntity patientRawDataEntity = patientRawDataMapper.selectOne(new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno)
                .isNotNull("data_json")
                .orderByAsc("data_date")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        if (patientRawDataEntity == null || !StringUtils.hasText(patientRawDataEntity.getDataJson())) {
            return null;
        }
        JsonNode jsonNode = AgentUtils.parseToNode(patientRawDataEntity.getDataJson());
        JsonNode illnessCourseNodeArray = jsonNode.get("pat_illnessCourse");
        if (illnessCourseNodeArray == null || !illnessCourseNodeArray.isArray()) {
            return null;
        }
        String firstIllnessCourse = null;
        for (JsonNode illnessCourseNode : illnessCourseNodeArray) {
            String itemname = illnessCourseNode.path("itemname").asText();
            if (IllnessRecordType.FIRST_COURSE.matches(itemname)) {
                firstIllnessCourse = illnessCourseNode.path("illnesscontent").asText();
            }
        }
        return firstIllnessCourse;
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

    private JsonNode parseJsonQuietly(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("JSON解析失败，忽略当前片段");
            return null;
        }
    }

    private String writeJsonQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON序列化失败", e);
        }
    }

    private PatientCourseData buildCourseData(String reqno,
                                              LocalDateTime lastTime,
                                              LocalDateTime sourceLastTime,
                                              EnumSet<PatientCourseDataType> requestedTypes) {
        PatientCourseData data = new PatientCourseData();
        data.setPatInfor(patientRawDataMapper.selectPatInfor(reqno));
        EnumSet<PatientCourseDataType> effectiveTypes = requestedTypes == null || requestedTypes.isEmpty()
                ? EnumSet.noneOf(PatientCourseDataType.class)
                : EnumSet.copyOf(requestedTypes);
        boolean fullSnapshot = effectiveTypes.contains(PatientCourseDataType.FULL_PATIENT);

        data.setPatDiagInforList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.DIAGNOSIS)
                ? patientRawDataMapper.selectDiagByReqno(reqno, lastTime)
                : new ArrayList<>());
        data.setPatBodySurfaceList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.BODY_SURFACE)
                ? patientRawDataMapper.selectBodySurfaceByReqno(reqno, lastTime)
                : new ArrayList<>());
        data.setLongDoctorAdviceList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.DOCTOR_ADVICE)
                ? patientRawDataMapper.selectLongDoctorAdviceByReqno(reqno, lastTime)
                : new ArrayList<>());
        data.setTemporaryDoctorAdviceList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.DOCTOR_ADVICE)
                ? patientRawDataMapper.selectTemporaryDoctorAdviceByReqno(reqno, lastTime)
                : new ArrayList<>());
        data.setSgDoctorAdviceList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.DOCTOR_ADVICE)
                ? patientRawDataMapper.selectSgDoctorAdviceByReqno(reqno, lastTime)
                : new ArrayList<>());
        data.setPatIllnessCourseList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.ILLNESS_COURSE)
                ? patientRawDataMapper.selectIllnessCourseByReqno(reqno, lastTime)
                : new ArrayList<>());

        List<PatientCourseData.PatTestSam> testSamList = new ArrayList<>();
        if (shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.LAB_TEST)) {
            testSamList = patientRawDataMapper.selectTestSamByReqno(reqno, lastTime);
            for (PatientCourseData.PatTestSam testSam : nonNullList(testSamList)) {
                testSam.setResultList(patientRawDataMapper.selectTestResultBySamreqno(reqno, testSam.getSamreqno(), lastTime));
            }
        }
        data.setPatTestSamList(testSamList);

        data.setPatUseMedicineList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.USE_MEDICINE)
                ? patientRawDataMapper.selectUseMedicineByReqno(reqno, lastTime)
                : new ArrayList<>());
        data.setPatVideoResultList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.VIDEO_RESULT)
                ? patientRawDataMapper.selectVideoResultByReqno(reqno, lastTime)
                : new ArrayList<>());
        data.setPatTransferList(shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.TRANSFER)
                ? patientRawDataMapper.selectTransferByReqno(reqno, lastTime)
                : new ArrayList<>());

        List<PatientCourseData.PatOpsCutInfor> opsList = new ArrayList<>();
        if (shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.OPERATION)) {
            opsList = patientRawDataMapper.selectOpsByReqno(reqno, lastTime);
            for (PatientCourseData.PatOpsCutInfor ops : nonNullList(opsList)) {
                ops.setPreWardMedicineList(patientRawDataMapper.selectPreWardOpsMedicine(reqno, ops.getOpsId(), lastTime));
                ops.setPerioperativeMedicineList(patientRawDataMapper.selectPerioperativeOpsMedicine(reqno, ops.getOpsId(), lastTime));
            }
        }
        data.setPatOpsCutInforList(opsList);

        List<PatientCourseData.PatTest> patTestList = new ArrayList<>();
        if (shouldLoad(fullSnapshot, effectiveTypes, PatientCourseDataType.MICROBE)) {
            patTestList = patientRawDataMapper.selectPatTestByReqno(reqno, lastTime);
            for (PatientCourseData.PatTest patTest : nonNullList(patTestList)) {
                List<PatientCourseData.MicrobeInfo> microbeList =
                        patientRawDataMapper.selectMicrobeBySamreqno(reqno, patTest.getSamreqno(), lastTime);
                for (PatientCourseData.MicrobeInfo microbeInfo : nonNullList(microbeList)) {
                    microbeInfo.setAntiDrugList(
                            patientRawDataMapper.selectAntiDrugByMicrobe(reqno, microbeInfo.getSamreqno(), microbeInfo.getDataCode(), lastTime));
                }
                patTest.setMicrobeList(microbeList);
            }
        }
        data.setPatTestList(patTestList);

        PatientCourseData.OtherInfo otherInfo = new PatientCourseData.OtherInfo();
        otherInfo.setQueryTime(DateTimeUtils.now());
        otherInfo.setSourceLastTime(sourceLastTime);
        otherInfo.setDataStartTime(lastTime);
        data.setOtherInfo(otherInfo);


        return data;
    }

    private boolean shouldLoad(boolean fullSnapshot,
                               EnumSet<PatientCourseDataType> requestedTypes,
                               PatientCourseDataType targetType) {
        return fullSnapshot || requestedTypes.contains(targetType);
    }

    private boolean hasPatientRawData(String reqno) {
        return patientRawDataMapper.selectCount(new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno)) > 0;
    }

    private PatientRawDataEntity insertPatientRawData(String reqno,
                                                      DailyPatientRawData dailyData,
                                                      LocalDateTime now,
                                                      String firstCourse) {
        PatientRawDataEntity rawDataEntity = new PatientRawDataEntity();
        rawDataEntity.setReqno(reqno);
        rawDataEntity.setDataDate(dailyData.getDataDate());
        rawDataEntity.setDataJson(buildSemanticBlockJson(dailyData));
        rawDataEntity.setFilterDataJson(buildFilterDataJson(dailyData, firstCourse));
        rawDataEntity.setClinicalNotes(String.join(" ", buildClinicalNotes(dailyData.getPatIllnessCourseList())));
        rawDataEntity.setStructDataJson(null);
        rawDataEntity.setEventJson(null);
        rawDataEntity.setCreateTime(now);
        rawDataEntity.setLastTime(now);
        rawDataEntity.setIsDel(0);
        patientRawDataMapper.insert(rawDataEntity);
        return rawDataEntity;
    }

    private PatientRawDataEntity upsertPatientRawData(String reqno,
                                                      DailyPatientRawData dailyData,
                                                      LocalDateTime now,
                                                      String firstCourse) {
        PatientRawDataEntity existing = patientRawDataMapper.selectOne(new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno)
                .eq("data_date", dailyData.getDataDate())
                .orderByAsc("data_date")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        if (existing == null) {
            return insertPatientRawData(reqno, dailyData, now, firstCourse);
        }
        PatientRawDataEntity update = new PatientRawDataEntity();
        update.setId(existing.getId());
        update.setDataJson(buildSemanticBlockJson(dailyData));
        update.setFilterDataJson(buildFilterDataJson(dailyData, firstCourse));
        update.setClinicalNotes(String.join(" ", buildClinicalNotes(dailyData.getPatIllnessCourseList())));
        update.setLastTime(now);
        patientRawDataMapper.updateById(update);
        existing.setLastTime(now);
        return existing;
    }

    private PatientRawDataChangeTaskEntity buildChangeTask(PatientRawDataEntity rawDataEntity,
                                                           LocalDateTime sourceBatchTime) {
        if (rawDataEntity == null || rawDataEntity.getId() == null || !StringUtils.hasText(rawDataEntity.getReqno())
                || rawDataEntity.getLastTime() == null) {
            return null;
        }
        PatientRawDataChangeTaskEntity task = new PatientRawDataChangeTaskEntity();
        task.setPatientRawDataId(rawDataEntity.getId());
        task.setReqno(rawDataEntity.getReqno());
        task.setDataDate(rawDataEntity.getDataDate());
        task.setRawDataLastTime(rawDataEntity.getLastTime());
        task.setSourceBatchTime(sourceBatchTime);
        task.setCreateTime(DateTimeUtils.now());
        return task;
    }

    private void enqueueDownstreamTasks(List<PatientRawDataChangeTaskEntity> changedTasks,
                                        PatientRawDataEntity rawDataEntity,
                                        EnumSet<PatientCourseDataType> changedTypes,
                                        DailyPatientRawData dailyData,
                                        LocalDateTime sourceBatchTime) {
        PatientRawDataChangeTaskEntity changeTask = buildChangeTask(rawDataEntity, sourceBatchTime);
        if (changeTask != null) {
            changedTasks.add(changeTask);
        }
        routeEventTask(rawDataEntity, changedTypes, dailyData, sourceBatchTime);
    }

    private void routeEventTask(PatientRawDataEntity rawDataEntity,
                                EnumSet<PatientCourseDataType> changedTypes,
                                DailyPatientRawData dailyData,
                                LocalDateTime sourceBatchTime) {
        if (rawDataEntity == null || rawDataEntity.getId() == null || sourceBatchTime == null) {
            return;
        }
        LinkedHashSet<InfectionEventTriggerReasonCode> reasonCodes = resolveEventTriggerReasonCodes(changedTypes, dailyData);
        if (reasonCodes.isEmpty()) {
            return;
        }
        int priority = reasonCodes.contains(InfectionEventTriggerReasonCode.ILLNESS_COURSE_CHANGED) ? 50 : 100;
        infectionEventTaskService.upsertEventExtractTask(
                rawDataEntity.getId(),
                rawDataEntity.getReqno(),
                rawDataEntity.getDataDate(),
                rawDataEntity.getLastTime(),
                sourceBatchTime,
                PatientCourseDataType.toCsv(changedTypes),
                InfectionEventTriggerReasonCode.toCsv(reasonCodes),
                priority
        );
    }

    private LinkedHashSet<InfectionEventTriggerReasonCode> resolveEventTriggerReasonCodes(EnumSet<PatientCourseDataType> changedTypes,
                                                                                          DailyPatientRawData dailyData) {
        LinkedHashSet<InfectionEventTriggerReasonCode> codes = new LinkedHashSet<>();
        if (changedTypes == null || changedTypes.isEmpty()) {
            return codes;
        }
        if (changedTypes.contains(PatientCourseDataType.FULL_PATIENT)) {
            appendReasonCodeWhenPresent(codes, InfectionEventTriggerReasonCode.ILLNESS_COURSE_CHANGED, dailyData == null ? null : dailyData.getPatIllnessCourseList());
            appendReasonCodeWhenPresent(codes, InfectionEventTriggerReasonCode.LAB_RESULT_CHANGED, dailyData == null ? null : dailyData.getPatTestSamList());
            appendReasonCodeWhenPresent(codes, InfectionEventTriggerReasonCode.MICROBE_CHANGED, dailyData == null ? null : dailyData.getPatTestList());
            appendReasonCodeWhenPresent(codes, InfectionEventTriggerReasonCode.IMAGING_CHANGED, dailyData == null ? null : dailyData.getPatVideoResultList());
            appendReasonCodeWhenPresent(codes, InfectionEventTriggerReasonCode.ANTIBIOTIC_OR_ORDER_CHANGED, dailyData == null ? null : dailyData.getPatUseMedicineList());
            appendReasonCodeWhenPresent(codes, InfectionEventTriggerReasonCode.OPERATION_CHANGED, dailyData == null ? null : dailyData.getPatOpsCutInforList());
            appendReasonCodeWhenPresent(codes, InfectionEventTriggerReasonCode.TRANSFER_CHANGED, dailyData == null ? null : dailyData.getPatTransferList());
            appendReasonCodeWhenPresent(codes, InfectionEventTriggerReasonCode.VITAL_SIGN_CHANGED, dailyData == null ? null : dailyData.getPatBodySurfaceList());
            return codes;
        }

        for (PatientCourseDataType changedType : changedTypes) {
            switch (changedType) {
                case ILLNESS_COURSE -> codes.add(InfectionEventTriggerReasonCode.ILLNESS_COURSE_CHANGED);
                case LAB_TEST -> codes.add(InfectionEventTriggerReasonCode.LAB_RESULT_CHANGED);
                case MICROBE -> codes.add(InfectionEventTriggerReasonCode.MICROBE_CHANGED);
                case VIDEO_RESULT -> codes.add(InfectionEventTriggerReasonCode.IMAGING_CHANGED);
                case USE_MEDICINE, DOCTOR_ADVICE -> codes.add(InfectionEventTriggerReasonCode.ANTIBIOTIC_OR_ORDER_CHANGED);
                case OPERATION -> codes.add(InfectionEventTriggerReasonCode.OPERATION_CHANGED);
                case TRANSFER -> codes.add(InfectionEventTriggerReasonCode.TRANSFER_CHANGED);
                case BODY_SURFACE -> codes.add(InfectionEventTriggerReasonCode.VITAL_SIGN_CHANGED);
                default -> {
                }
            }
        }
        return codes;
    }

    private void appendReasonCodeWhenPresent(LinkedHashSet<InfectionEventTriggerReasonCode> codes,
                                             InfectionEventTriggerReasonCode code,
                                             List<?> items) {
        if (codes != null && code != null && items != null && !items.isEmpty()) {
            codes.add(code);
        }
    }

    private EnumSet<PatientCourseDataType> detectChangedDataTypes(String reqno,
                                                                  LocalDateTime previousSourceLastTime) {
        return PatientCourseDataType.fromNames(
                patientRawDataMapper.selectChangedDataTypes(reqno, previousSourceLastTime)
        );
    }

    private PatientRawDataEntity findPatientRawDataByReqnoAndDate(String reqno, LocalDate dataDate) {
        return patientRawDataMapper.selectOne(new QueryWrapper<PatientRawDataEntity>()
                .eq("reqno", reqno)
                .eq("data_date", dataDate)
                .orderByAsc("data_date")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
    }

    private boolean hasRequiredRawSections(String dataJson, EnumSet<PatientCourseDataType> changedTypes) {
        if (!StringUtils.hasText(dataJson) || changedTypes == null || changedTypes.isEmpty()) {
            return false;
        }
        JsonNode root = AgentUtils.parseToNode(dataJson);
        if (root == null || !root.isObject()) {
            return false;
        }
        for (PatientCourseDataType type : changedTypes) {
            if (type == PatientCourseDataType.FULL_PATIENT) {
                return false;
            }
            if (!hasTypeRawSection(root, type)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasTypeRawSection(JsonNode root, PatientCourseDataType type) {
        return switch (type) {
            case DIAGNOSIS -> root.has("pat_diagInfor");
            case BODY_SURFACE -> root.has("pat_bodySurface");
            case DOCTOR_ADVICE -> root.has("pat_doctorAdvice_long")
                    && root.has("pat_doctorAdvice_temporary")
                    && root.has("pat_doctorAdvice_sg");
            case ILLNESS_COURSE -> root.has("pat_illnessCourse");
            case LAB_TEST -> root.has("pat_testSam");
            case USE_MEDICINE -> root.has("pat_useMedicine");
            case VIDEO_RESULT -> root.has("pat_videoResult");
            case TRANSFER -> root.has("pat_transfer");
            case OPERATION -> root.has("pat_opsCutInfor");
            case MICROBE -> root.has("pat_test");
            case FULL_PATIENT -> false;
        };
    }

    private PatientRawDataEntity mergeChangedRawData(PatientRawDataEntity existing,
                                                     DailyPatientRawData changedDailyData,
                                                     EnumSet<PatientCourseDataType> changedTypes,
                                                     LocalDateTime now,
                                                     String firstCourse) {
        try {
            ObjectNode root = parseObjectNode(existing.getDataJson());
            root.put("reqno", existing.getReqno());
            if (existing.getDataDate() != null) {
                root.put("dataDate", existing.getDataDate().toString());
            }
            root.put("admission_time", buildAdmissionTime(changedDailyData.getPatInfor()));
            root.put("patient_summary", buildPatientSummary(changedDailyData.getPatInfor()));

            for (PatientCourseDataType changedType : changedTypes) {
                switch (changedType) {
                    case DIAGNOSIS -> {
                        List<PatientCourseData.PatDiagInfor> mergedDiag = mergeByKey(
                                readList(root, "pat_diagInfor", new TypeReference<List<PatientCourseData.PatDiagInfor>>() {}),
                                changedDailyData.getPatDiagInforList(),
                                this::diagKey
                        );
                        root.set("pat_diagInfor", objectMapper.valueToTree(mergedDiag));
                    }
                    case BODY_SURFACE -> {
                        List<PatientCourseData.PatBodySurface> mergedBodySurface = mergeByKey(
                                readList(root, "pat_bodySurface", new TypeReference<List<PatientCourseData.PatBodySurface>>() {}),
                                changedDailyData.getPatBodySurfaceList(),
                                this::bodySurfaceKey
                        );
                        root.set("pat_bodySurface", objectMapper.valueToTree(mergedBodySurface));
                    }
                    case DOCTOR_ADVICE -> {
                        List<PatientCourseData.PatDoctorAdvice> mergedLong = mergeByKey(
                                readList(root, "pat_doctorAdvice_long", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {}),
                                changedDailyData.getLongDoctorAdviceList(),
                                this::doctorAdviceKey
                        );
                        List<PatientCourseData.PatDoctorAdvice> mergedTemporary = mergeByKey(
                                readList(root, "pat_doctorAdvice_temporary", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {}),
                                changedDailyData.getTemporaryDoctorAdviceList(),
                                this::doctorAdviceKey
                        );
                        List<PatientCourseData.PatDoctorAdvice> mergedSg = mergeByKey(
                                readList(root, "pat_doctorAdvice_sg", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {}),
                                changedDailyData.getSgDoctorAdviceList(),
                                this::doctorAdviceKey
                        );
                        root.set("pat_doctorAdvice_long", objectMapper.valueToTree(mergedLong));
                        root.set("pat_doctorAdvice_temporary", objectMapper.valueToTree(mergedTemporary));
                        root.set("pat_doctorAdvice_sg", objectMapper.valueToTree(mergedSg));
                    }
                    case ILLNESS_COURSE -> {
                        List<PatientCourseData.PatIllnessCourse> mergedCourse = mergeByKey(
                                readList(root, "pat_illnessCourse", new TypeReference<List<PatientCourseData.PatIllnessCourse>>() {}),
                                changedDailyData.getPatIllnessCourseList(),
                                this::illnessCourseKey
                        );
                        root.set("pat_illnessCourse", objectMapper.valueToTree(mergedCourse));
                    }
                    case LAB_TEST -> {
                        List<PatientCourseData.PatTestSam> mergedTestSam = mergePatTestSamList(
                                readList(root, "pat_testSam", new TypeReference<List<PatientCourseData.PatTestSam>>() {}),
                                changedDailyData.getPatTestSamList()
                        );
                        root.set("pat_testSam", objectMapper.valueToTree(mergedTestSam));
                    }
                    case USE_MEDICINE -> {
                        List<PatientCourseData.PatUseMedicine> mergedUseMedicine = mergeByKey(
                                readList(root, "pat_useMedicine", new TypeReference<List<PatientCourseData.PatUseMedicine>>() {}),
                                changedDailyData.getPatUseMedicineList(),
                                this::useMedicineKey
                        );
                        root.set("pat_useMedicine", objectMapper.valueToTree(mergedUseMedicine));
                    }
                    case VIDEO_RESULT -> {
                        List<PatientCourseData.PatVideoResult> mergedVideoResult = mergeByKey(
                                readList(root, "pat_videoResult", new TypeReference<List<PatientCourseData.PatVideoResult>>() {}),
                                changedDailyData.getPatVideoResultList(),
                                this::videoResultKey
                        );
                        root.set("pat_videoResult", objectMapper.valueToTree(mergedVideoResult));
                    }
                    case TRANSFER -> {
                        List<PatientCourseData.PatTransfer> mergedTransfer = mergeByKey(
                                readList(root, "pat_transfer", new TypeReference<List<PatientCourseData.PatTransfer>>() {}),
                                changedDailyData.getPatTransferList(),
                                this::transferKey
                        );
                        root.set("pat_transfer", objectMapper.valueToTree(mergedTransfer));
                    }
                    case OPERATION -> {
                        List<PatientCourseData.PatOpsCutInfor> mergedOps = mergePatOpsCutInforList(
                                readList(root, "pat_opsCutInfor", new TypeReference<List<PatientCourseData.PatOpsCutInfor>>() {}),
                                changedDailyData.getPatOpsCutInforList()
                        );
                        root.set("pat_opsCutInfor", objectMapper.valueToTree(mergedOps));
                    }
                    case MICROBE -> {
                        List<PatientCourseData.PatTest> mergedPatTest = mergePatTestList(
                                readList(root, "pat_test", new TypeReference<List<PatientCourseData.PatTest>>() {}),
                                changedDailyData.getPatTestList()
                        );
                        root.set("pat_test", objectMapper.valueToTree(mergedPatTest));
                    }
                    case FULL_PATIENT -> {
                        // fallback path already handles full rebuild, no-op here
                    }
                }
            }

            PatientRawDataEntity update = new PatientRawDataEntity();
            update.setId(existing.getId());
            update.setDataJson(objectMapper.writeValueAsString(root));
            update.setFilterDataJson(mergeFilterDataJson(existing.getFilterDataJson(), root, changedTypes, firstCourse));
            update.setLastTime(now);
            patientRawDataMapper.updateById(update);
            existing.setLastTime(now);
            return existing;
        } catch (Exception e) {
            log.warn("按主键合并原始数据失败，reqno={}, dataDate={}", existing.getReqno(), existing.getDataDate(), e);
            return upsertPatientRawData(existing.getReqno(), changedDailyData, now, firstCourse);
        }
    }

    private ObjectNode parseObjectNode(String dataJson) {
        JsonNode node = AgentUtils.parseToNode(dataJson);
        if (node != null && node.isObject()) {
            return (ObjectNode) node.deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private <T> List<T> readList(ObjectNode root, String fieldName, TypeReference<List<T>> typeReference) {
        if (root == null || !root.has(fieldName) || root.get(fieldName) == null || root.get(fieldName).isNull()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.convertValue(root.get(fieldName), typeReference);
        } catch (IllegalArgumentException e) {
            return new ArrayList<>();
        }
    }

    private <T> List<T> mergeByKey(List<T> existingItems, List<T> changedItems, Function<T, String> keyExtractor) {
        Map<String, T> merged = new LinkedHashMap<>();
        int index = 0;
        for (T existingItem : nonNullList(existingItems)) {
            merged.put(normalizeKey(keyExtractor.apply(existingItem), "existing-" + index), existingItem);
            index++;
        }
        for (T changedItem : nonNullList(changedItems)) {
            String changedKey = normalizeKey(keyExtractor.apply(changedItem), null);
            if (changedKey != null && merged.containsKey(changedKey)) {
                merged.put(changedKey, changedItem);
            } else {
                merged.put(normalizeKey(keyExtractor.apply(changedItem), "changed-" + index), changedItem);
            }
            index++;
        }
        return new ArrayList<>(merged.values());
    }

    private List<PatientCourseData.PatTestSam> mergePatTestSamList(List<PatientCourseData.PatTestSam> existingItems,
                                                                   List<PatientCourseData.PatTestSam> changedItems) {
        Map<String, PatientCourseData.PatTestSam> merged = new LinkedHashMap<>();
        int index = 0;
        for (PatientCourseData.PatTestSam existingItem : nonNullList(existingItems)) {
            merged.put(normalizeKey(testSamKey(existingItem), "test-sam-existing-" + index), existingItem);
            index++;
        }
        for (PatientCourseData.PatTestSam changedItem : nonNullList(changedItems)) {
            String key = normalizeKey(testSamKey(changedItem), "test-sam-changed-" + index);
            PatientCourseData.PatTestSam existing = merged.get(key);
            if (existing != null) {
                changedItem.setResultList(mergeByKey(existing.getResultList(), changedItem.getResultList(), this::testResultKey));
            }
            merged.put(key, changedItem);
            index++;
        }
        return new ArrayList<>(merged.values());
    }

    private List<PatientCourseData.PatOpsCutInfor> mergePatOpsCutInforList(List<PatientCourseData.PatOpsCutInfor> existingItems,
                                                                            List<PatientCourseData.PatOpsCutInfor> changedItems) {
        Map<String, PatientCourseData.PatOpsCutInfor> merged = new LinkedHashMap<>();
        int index = 0;
        for (PatientCourseData.PatOpsCutInfor existingItem : nonNullList(existingItems)) {
            merged.put(normalizeKey(opsKey(existingItem), "ops-existing-" + index), existingItem);
            index++;
        }
        for (PatientCourseData.PatOpsCutInfor changedItem : nonNullList(changedItems)) {
            String key = normalizeKey(opsKey(changedItem), "ops-changed-" + index);
            PatientCourseData.PatOpsCutInfor existing = merged.get(key);
            if (existing != null) {
                changedItem.setPreWardMedicineList(mergeByKey(existing.getPreWardMedicineList(),
                        changedItem.getPreWardMedicineList(), this::opsMedicineKey));
                changedItem.setPerioperativeMedicineList(mergeByKey(existing.getPerioperativeMedicineList(),
                        changedItem.getPerioperativeMedicineList(), this::opsMedicineKey));
            }
            merged.put(key, changedItem);
            index++;
        }
        return new ArrayList<>(merged.values());
    }

    private List<PatientCourseData.PatTest> mergePatTestList(List<PatientCourseData.PatTest> existingItems,
                                                             List<PatientCourseData.PatTest> changedItems) {
        Map<String, PatientCourseData.PatTest> merged = new LinkedHashMap<>();
        int index = 0;
        for (PatientCourseData.PatTest existingItem : nonNullList(existingItems)) {
            merged.put(normalizeKey(patTestKey(existingItem), "pat-test-existing-" + index), existingItem);
            index++;
        }
        for (PatientCourseData.PatTest changedItem : nonNullList(changedItems)) {
            String key = normalizeKey(patTestKey(changedItem), "pat-test-changed-" + index);
            PatientCourseData.PatTest existing = merged.get(key);
            if (existing != null) {
                changedItem.setMicrobeList(mergeMicrobeList(existing.getMicrobeList(), changedItem.getMicrobeList()));
            }
            merged.put(key, changedItem);
            index++;
        }
        return new ArrayList<>(merged.values());
    }

    private List<PatientCourseData.MicrobeInfo> mergeMicrobeList(List<PatientCourseData.MicrobeInfo> existingItems,
                                                                 List<PatientCourseData.MicrobeInfo> changedItems) {
        Map<String, PatientCourseData.MicrobeInfo> merged = new LinkedHashMap<>();
        int index = 0;
        for (PatientCourseData.MicrobeInfo existingItem : nonNullList(existingItems)) {
            merged.put(normalizeKey(microbeKey(existingItem), "microbe-existing-" + index), existingItem);
            index++;
        }
        for (PatientCourseData.MicrobeInfo changedItem : nonNullList(changedItems)) {
            String key = normalizeKey(microbeKey(changedItem), "microbe-changed-" + index);
            PatientCourseData.MicrobeInfo existing = merged.get(key);
            if (existing != null) {
                changedItem.setAntiDrugList(mergeByKey(existing.getAntiDrugList(), changedItem.getAntiDrugList(), this::antiDrugKey));
            }
            merged.put(key, changedItem);
            index++;
        }
        return new ArrayList<>(merged.values());
    }

    private String normalizeKey(String key, String fallback) {
        if (StringUtils.hasText(key)) {
            return key;
        }
        return fallback;
    }

    private String diagKey(PatientCourseData.PatDiagInfor item) {
        return joinKey(item == null ? null : item.getReqno(),
                item == null ? null : item.getDiagId(),
                formatDateTime(item == null ? null : item.getDiagTime(), DateTimeUtils.DATE_TIME_PATTERN));
    }

    private String bodySurfaceKey(PatientCourseData.PatBodySurface item) {
        return joinKey(item == null ? null : item.getReqno(),
                formatDateTime(item == null ? null : item.getMeasuredate(), DateTimeUtils.DATE_TIME_PATTERN),
                item == null ? null : item.getFlag());
    }

    private String doctorAdviceKey(PatientCourseData.PatDoctorAdvice item) {
        return joinKey(item == null ? null : item.getReqno(),
                item == null ? null : item.getDocadvno(),
                formatDateTime(item == null ? null : item.getBegtime(), DateTimeUtils.DATE_TIME_PATTERN));
    }

    private String illnessCourseKey(PatientCourseData.PatIllnessCourse item) {
        return joinKey(item == null ? null : item.getReqno(),
                formatDateTime(item == null ? null : item.getCreattime(), DateTimeUtils.DATE_TIME_PATTERN),
                item == null ? null : item.getItemname());
    }

    private String testSamKey(PatientCourseData.PatTestSam item) {
        return joinKey(item == null ? null : item.getReqno(), item == null ? null : item.getSamreqno());
    }

    private String testResultKey(PatientCourseData.PatTestResult item) {
        return joinKey(item == null ? null : item.getReqno(),
                item == null ? null : item.getSamreqno(),
                item == null ? null : item.getItemno());
    }

    private String useMedicineKey(PatientCourseData.PatUseMedicine item) {
        return joinKey(item == null ? null : item.getReqno(),
                item == null ? null : item.getUseorderno(),
                item == null ? null : item.getMediId());
    }

    private String videoResultKey(PatientCourseData.PatVideoResult item) {
        return joinKey(item == null ? null : item.getReqno(),
                item == null ? null : item.getSamreqno(),
                item == null ? null : item.getItemno(),
                item == null ? null : item.getDocadvtime());
    }

    private String transferKey(PatientCourseData.PatTransfer item) {
        return joinKey(item == null ? null : item.getReqno(),
                formatDateTime(item == null ? null : item.getIndeptdate(), DateTimeUtils.DATE_TIME_PATTERN));
    }

    private String opsKey(PatientCourseData.PatOpsCutInfor item) {
        return joinKey(item == null ? null : item.getReqno(), item == null ? null : item.getOpsId());
    }

    private String opsMedicineKey(PatientCourseData.OpsMedicine item) {
        return joinKey(item == null ? null : item.getReqno(),
                item == null ? null : item.getUseorderno(),
                item == null ? null : item.getMediId());
    }

    private String patTestKey(PatientCourseData.PatTest item) {
        return joinKey(item == null ? null : item.getReqno(), item == null ? null : item.getSamreqno());
    }

    private String microbeKey(PatientCourseData.MicrobeInfo item) {
        return joinKey(item == null ? null : item.getReqno(),
                item == null ? null : item.getSamreqno(),
                item == null ? null : item.getDataCode());
    }

    private String antiDrugKey(PatientCourseData.AntiDrugInfo item) {
        return joinKey(item == null ? null : item.getReqno(),
                item == null ? null : item.getSamreqno(),
                item == null ? null : item.getMicrobecode(),
                item == null ? null : item.getDatano());
    }

    private String joinKey(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                parts.add(value.trim());
            }
        }
        return parts.isEmpty() ? null : String.join("|", parts);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
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
        LocalDate date = (time == null ? DateTimeUtils.today() : time.toLocalDate());
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
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("reqno", dailyData.getReqno());
            raw.put("dataDate", dailyData.getDataDate() == null ? null : dailyData.getDataDate().toString());
            raw.put("admission_time", buildAdmissionTime(dailyData.getPatInfor()));
            raw.put("patient_summary", buildPatientSummary(dailyData.getPatInfor()));
            raw.put("pat_diagInfor", dailyData.getPatDiagInforList());
            raw.put("pat_bodySurface", dailyData.getPatBodySurfaceList());
            raw.put("pat_doctorAdvice_long", dailyData.getLongDoctorAdviceList());
            raw.put("pat_doctorAdvice_temporary", dailyData.getTemporaryDoctorAdviceList());
            raw.put("pat_doctorAdvice_sg", dailyData.getSgDoctorAdviceList());
            raw.put("pat_illnessCourse", dailyData.getPatIllnessCourseList());
            raw.put("pat_testSam", dailyData.getPatTestSamList());
            raw.put("pat_useMedicine", dailyData.getPatUseMedicineList());
            raw.put("pat_videoResult", dailyData.getPatVideoResultList());
            raw.put("pat_transfer", dailyData.getPatTransferList());
            raw.put("pat_opsCutInfor", dailyData.getPatOpsCutInforList());
            raw.put("pat_test", dailyData.getPatTestList());
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            log.error("原始块JSON生成失败，回退普通序列化", e);
            return writeJson(dailyData);
        }
    }

    private String buildFilterDataJson(DailyPatientRawData dailyData, String firstCourse) {
        try {
            Map<String, Object> filtered = new LinkedHashMap<>();
            filtered.put("reqno", dailyData.getReqno());
            filtered.put("dataDate", dailyData.getDataDate() == null ? null : dailyData.getDataDate().toString());
            filtered.put("admission_time", buildAdmissionTime(dailyData.getPatInfor()));
            filtered.put("patient_summary", buildPatientSummary(dailyData.getPatInfor()));
            filtered.put("patient_info", buildPatientInfo(dailyData.getPatInfor()));
            filtered.put("diagnosis", buildDiagnosis(dailyData.getPatDiagInforList()));
            filtered.put("vital_signs", buildVitalSigns(dailyData.getPatBodySurfaceList()));
            filtered.put("lab_results", buildLabResults(dailyData.getPatTestSamList(), dailyData.getPatTestList()));
            filtered.put("imaging", buildImaging(dailyData.getPatVideoResultList()));
            filtered.put("doctor_orders",
                    buildDoctorOrders(dailyData.getLongDoctorAdviceList(),
                            dailyData.getTemporaryDoctorAdviceList(),
                            dailyData.getSgDoctorAdviceList()));
            filtered.put("use_medicine", buildUseMedicine(dailyData.getPatUseMedicineList()));
            filtered.put("transfer", buildTransfers(dailyData.getPatTransferList()));
            filtered.put("operation", buildOperations(dailyData.getPatOpsCutInforList()));
            List<PatientCourseData.PatIllnessCourse> filteredIllnessCourseList =
                    filterIllnessCourseList(dailyData.getPatIllnessCourseList(), firstCourse);
            filtered.put("clinical_notes", buildClinicalNotes(filteredIllnessCourseList));
            filtered.put("pat_illnessCourse", filteredIllnessCourseList);
            return objectMapper.writeValueAsString(filtered);
        } catch (Exception e) {
            log.error("过滤块JSON生成失败，回退空JSON", e);
            return "{}";
        }
    }

    private String mergeFilterDataJson(String existingFilterDataJson,
                                       ObjectNode rawRoot,
                                       EnumSet<PatientCourseDataType> changedTypes,
                                       String firstCourse) {
        try {
            ObjectNode filterRoot = parseObjectNode(existingFilterDataJson);
            filterRoot.put("reqno", rawRoot.path("reqno").asText(""));
            filterRoot.put("dataDate", rawRoot.path("dataDate").asText(""));
            filterRoot.put("admission_time", rawRoot.path("admission_time").asText(""));
            filterRoot.put("patient_summary", rawRoot.path("patient_summary").asText(""));

            if (filterRoot.path("patient_info").isMissingNode() || filterRoot.path("patient_info").isNull()) {
                filterRoot.set("patient_info", objectMapper.createObjectNode());
            }
            for (PatientCourseDataType changedType : changedTypes) {
                switch (changedType) {
                    case DIAGNOSIS -> filterRoot.set("diagnosis", objectMapper.valueToTree(buildDiagnosis(
                            readList(rawRoot, "pat_diagInfor", new TypeReference<List<PatientCourseData.PatDiagInfor>>() {}))));
                    case BODY_SURFACE -> filterRoot.set("vital_signs", objectMapper.valueToTree(buildVitalSigns(
                            readList(rawRoot, "pat_bodySurface", new TypeReference<List<PatientCourseData.PatBodySurface>>() {}))));
                    case DOCTOR_ADVICE -> filterRoot.set("doctor_orders", objectMapper.valueToTree(buildDoctorOrders(
                            readList(rawRoot, "pat_doctorAdvice_long", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {}),
                            readList(rawRoot, "pat_doctorAdvice_temporary", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {}),
                            readList(rawRoot, "pat_doctorAdvice_sg", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {}))));
                    case ILLNESS_COURSE -> {
                        List<PatientCourseData.PatIllnessCourse> filteredIllnessCourseList = filterIllnessCourseList(
                                readList(rawRoot, "pat_illnessCourse", new TypeReference<List<PatientCourseData.PatIllnessCourse>>() {}),
                                firstCourse
                        );
                        filterRoot.set("clinical_notes", objectMapper.valueToTree(buildClinicalNotes(filteredIllnessCourseList)));
                        filterRoot.set("pat_illnessCourse", objectMapper.valueToTree(filteredIllnessCourseList));
                    }
                    case LAB_TEST, MICROBE -> filterRoot.set("lab_results", objectMapper.valueToTree(buildLabResults(
                            readList(rawRoot, "pat_testSam", new TypeReference<List<PatientCourseData.PatTestSam>>() {}),
                            readList(rawRoot, "pat_test", new TypeReference<List<PatientCourseData.PatTest>>() {}))));
                    case VIDEO_RESULT -> filterRoot.set("imaging", objectMapper.valueToTree(buildImaging(
                            readList(rawRoot, "pat_videoResult", new TypeReference<List<PatientCourseData.PatVideoResult>>() {}))));
                    case USE_MEDICINE -> filterRoot.set("use_medicine", objectMapper.valueToTree(buildUseMedicine(
                            readList(rawRoot, "pat_useMedicine", new TypeReference<List<PatientCourseData.PatUseMedicine>>() {}))));
                    case TRANSFER -> filterRoot.set("transfer", objectMapper.valueToTree(buildTransfers(
                            readList(rawRoot, "pat_transfer", new TypeReference<List<PatientCourseData.PatTransfer>>() {}))));
                    case OPERATION -> filterRoot.set("operation", objectMapper.valueToTree(buildOperations(
                            readList(rawRoot, "pat_opsCutInfor", new TypeReference<List<PatientCourseData.PatOpsCutInfor>>() {}))));
                    case FULL_PATIENT -> {
                        // patient_info remains managed by full rebuild path
                    }
                }
            }
            return objectMapper.writeValueAsString(filterRoot);
        } catch (Exception e) {
            log.warn("合并过滤块JSON失败，回退全量重建", e);
            return rebuildFilterDataJsonFromRaw(rawRoot, firstCourse);
        }
    }

    private String rebuildFilterDataJsonFromRaw(ObjectNode rawRoot, String firstCourse) {
        try {
            Map<String, Object> filtered = new LinkedHashMap<>();
            filtered.put("reqno", rawRoot.path("reqno").asText(""));
            filtered.put("dataDate", rawRoot.path("dataDate").asText(""));
            filtered.put("admission_time", rawRoot.path("admission_time").asText(""));
            filtered.put("patient_summary", rawRoot.path("patient_summary").asText(""));
            filtered.put("patient_info", new LinkedHashMap<>());
            filtered.put("diagnosis", buildDiagnosis(
                    readList(rawRoot, "pat_diagInfor", new TypeReference<List<PatientCourseData.PatDiagInfor>>() {})));
            filtered.put("vital_signs", buildVitalSigns(
                    readList(rawRoot, "pat_bodySurface", new TypeReference<List<PatientCourseData.PatBodySurface>>() {})));
            filtered.put("lab_results", buildLabResults(
                    readList(rawRoot, "pat_testSam", new TypeReference<List<PatientCourseData.PatTestSam>>() {}),
                    readList(rawRoot, "pat_test", new TypeReference<List<PatientCourseData.PatTest>>() {})));
            filtered.put("imaging", buildImaging(
                    readList(rawRoot, "pat_videoResult", new TypeReference<List<PatientCourseData.PatVideoResult>>() {})));
            filtered.put("doctor_orders", buildDoctorOrders(
                    readList(rawRoot, "pat_doctorAdvice_long", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {}),
                    readList(rawRoot, "pat_doctorAdvice_temporary", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {}),
                    readList(rawRoot, "pat_doctorAdvice_sg", new TypeReference<List<PatientCourseData.PatDoctorAdvice>>() {})));
            filtered.put("use_medicine", buildUseMedicine(
                    readList(rawRoot, "pat_useMedicine", new TypeReference<List<PatientCourseData.PatUseMedicine>>() {})));
            filtered.put("transfer", buildTransfers(
                    readList(rawRoot, "pat_transfer", new TypeReference<List<PatientCourseData.PatTransfer>>() {})));
            filtered.put("operation", buildOperations(
                    readList(rawRoot, "pat_opsCutInfor", new TypeReference<List<PatientCourseData.PatOpsCutInfor>>() {})));
            List<PatientCourseData.PatIllnessCourse> filteredIllnessCourseList = filterIllnessCourseList(
                    readList(rawRoot, "pat_illnessCourse", new TypeReference<List<PatientCourseData.PatIllnessCourse>>() {}),
                    firstCourse
            );
            filtered.put("clinical_notes", buildClinicalNotes(filteredIllnessCourseList));
            filtered.put("pat_illnessCourse", filteredIllnessCourseList);
            return objectMapper.writeValueAsString(filtered);
        } catch (Exception e) {
            return "{}";
        }
    }

    private List<PatientCourseData.PatIllnessCourse> filterIllnessCourseList(List<PatientCourseData.PatIllnessCourse> illnessCourseList,
                                                                              String firstCourse) {
        List<PatientCourseData.PatIllnessCourse> filteredList = new ArrayList<>();
        String normalizedFirstCourse = StringUtils.hasText(firstCourse) ? firstCourse : "";
        for (PatientCourseData.PatIllnessCourse illnessCourse : nonNullList(illnessCourseList)) {
            PatientCourseData.PatIllnessCourse copied = objectMapper.convertValue(illnessCourse, PatientCourseData.PatIllnessCourse.class);
            String itemname = copied.getItemname();
            String illnesscontent = copied.getIllnesscontent();
            if (IllnessRecordType.FIRST_COURSE.matches(itemname)) {
                copied.setIllnesscontent(normalizedFirstCourse);
            } else {
                copied.setIllnesscontent(filterTextUtils.filterContent(normalizedFirstCourse, illnesscontent));
            }
            filteredList.add(copied);
        }
        return filteredList;
    }

    private String resolveFirstIllnessCourse(String reqno, List<PatientCourseData.PatIllnessCourse> courseDataList) {
        String firstCourse = getInhosdateRaw(reqno);
        if (StringUtils.hasText(firstCourse)) {
            return firstCourse;
        }
        for (PatientCourseData.PatIllnessCourse illnessCourse : courseDataList) {
            if (IllnessRecordType.FIRST_COURSE.matches(illnessCourse.getItemname())) {
                return illnessCourse.getIllnesscontent();
            }
        }
        return null;
    }

    private Map<String, Object> buildPatientInfo(PatientCourseData.PatInfor patInfor) {
        Map<String, Object> patientInfo = new LinkedHashMap<>();
        if (patInfor == null) {
            return patientInfo;
        }
        patientInfo.put("sex", patInfor.getSex());
        patientInfo.put("age", parseAgeNumber(patInfor.getAge()));
        patientInfo.put("admission_time", buildAdmissionTime(patInfor));
        patientInfo.put("department", patInfor.getDisname());
        return patientInfo;
    }

    private String buildAdmissionTime(PatientCourseData.PatInfor patInfor) {
        if (patInfor == null) {
            return null;
        }
        return formatDateTime(patInfor.getInhosday(), "yyyy-MM-dd HH:mm");
    }

    private String buildPatientSummary(PatientCourseData.PatInfor patInfor) {
        if (patInfor == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        String admissionDate = formatDateTime(patInfor.getInhosday(), "yyyy-MM-dd");
        if (StringUtils.hasText(admissionDate)) {
            parts.add("患者入院时间：" + admissionDate);
        }
        if (StringUtils.hasText(patInfor.getSex())) {
            parts.add("性别：" + patInfor.getSex().trim());
        }
        String displayAge = formatDisplayAge(patInfor.getAge());
        if (StringUtils.hasText(displayAge)) {
            parts.add("年龄：" + displayAge);
        }
        return parts.isEmpty() ? null : String.join("，", parts);
    }

    private String formatDisplayAge(String ageText) {
        if (!StringUtils.hasText(ageText)) {
            return null;
        }
        String trimmedAge = ageText.trim();
        return trimmedAge.matches("\\d+") ? trimmedAge + "岁" : trimmedAge;
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
            Map<String, Object> item = buildCompactVitalSignItem(bodySurface);
            if (!item.isEmpty()) {
                vitalSigns.add(item);
            }
        }
        return vitalSigns;
    }

    private Map<String, Object> buildCompactVitalSignItem(PatientCourseData.PatBodySurface bodySurface) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (bodySurface == null) {
            return item;
        }
        List<String> abnormalFields = new ArrayList<>();
        String time = formatDateTime(bodySurface.getMeasuredate(), "HH:mm");
        if (StringUtils.hasText(time)) {
            item.put("time", time);
        }
        if (StringUtils.hasText(bodySurface.getTemperature())) {
            item.put("temp", bodySurface.getTemperature());
            if (isAbnormalTemperature(bodySurface.getTemperature())) {
                abnormalFields.add("temp");
            }
        }
        if (StringUtils.hasText(bodySurface.getStoolCount())) {
            item.put("stool", bodySurface.getStoolCount());
            if (isAbnormalStoolCount(bodySurface.getStoolCount())) {
                abnormalFields.add("stool");
            }
        }
        if (StringUtils.hasText(bodySurface.getPulse())) {
            item.put("pulse", bodySurface.getPulse());
            if (isAbnormalPulse(bodySurface.getPulse())) {
                abnormalFields.add("pulse");
            }
        }
        if (StringUtils.hasText(bodySurface.getBreath())) {
            item.put("resp", bodySurface.getBreath());
            if (isAbnormalRespiration(bodySurface.getBreath())) {
                abnormalFields.add("resp");
            }
        }
        if (StringUtils.hasText(bodySurface.getBloodPressure())) {
            item.put("bp", bodySurface.getBloodPressure());
            if (isAbnormalBloodPressure(bodySurface.getBloodPressure())) {
                abnormalFields.add("bp");
            }
        }
        if (item.isEmpty()) {
            return item;
        }
        item.put("abn", abnormalFields);
        return item;
    }

    private Map<String, Object> buildLabResults(List<PatientCourseData.PatTestSam> testSamList,
                                                List<PatientCourseData.PatTest> patTestList) {
        Map<String, Object> labResults = new LinkedHashMap<>();
        List<Map<String, Object>> testPanels = new ArrayList<>();
        for (PatientCourseData.PatTestSam sam : nonNullList(testSamList)) {
            List<Map<String, Object>> results = new ArrayList<>();
            int abnormalCount = 0;

            for (PatientCourseData.PatTestResult result : nonNullList(sam.getResultList())) {
                Map<String, Object> resultItem = buildCompactLabResultItem(result);
                if (!resultItem.isEmpty()) {
                    results.add(resultItem);
                    if (Boolean.TRUE.equals(resultItem.get("is_abnormal"))) {
                        abnormalCount++;
                    }
                }
            }

            if (!results.isEmpty()) {
                Map<String, Object> panel = new LinkedHashMap<>();
                String panelName = firstNonBlank(sam.getTestaim(), sam.getDataName());
                if (StringUtils.hasText(panelName)) {
                    panel.put("panel_name", panelName);
                }
                if (StringUtils.hasText(sam.getDataName()) && !sam.getDataName().equals(panelName)) {
                    panel.put("sample_type", sam.getDataName());
                }
                panel.put("test_time", formatDateTime(sam.getTestdate(), "yyyy-MM-dd HH:mm"));
                panel.put("abnormal_count", abnormalCount);
                panel.put("normal_count", Math.max(results.size() - abnormalCount, 0));
                panel.put("results", results);
                testPanels.add(panel);
            }
        }
        labResults.put("test_panels", testPanels);

        List<Map<String, Object>> microbePanels = new ArrayList<>();
        for (PatientCourseData.PatTest patTest : nonNullList(patTestList)) {
            List<Map<String, Object>> microbeResults = new ArrayList<>();
            int abnormalCount = 0;
            for (PatientCourseData.MicrobeInfo microbeInfo : nonNullList(patTest.getMicrobeList())) {
                Map<String, Object> microbeItem = buildCompactMicrobeResultItem(microbeInfo);
                if (!microbeItem.isEmpty()) {
                    microbeResults.add(microbeItem);
                    if (Boolean.TRUE.equals(microbeItem.get("is_abnormal"))) {
                        abnormalCount++;
                    }
                }
            }

            if (!microbeResults.isEmpty()) {
                Map<String, Object> microbePanel = new LinkedHashMap<>();
                String panelName = firstNonBlank(patTest.getTestobject(), patTest.getDataName());
                if (StringUtils.hasText(panelName)) {
                    microbePanel.put("panel_name", panelName);
                }
                if (StringUtils.hasText(patTest.getDataName()) && !patTest.getDataName().equals(panelName)) {
                    microbePanel.put("sample_type", patTest.getDataName());
                }
                microbePanel.put("sample_time", formatDateTime(patTest.getSampletime(), "yyyy-MM-dd HH:mm"));
                microbePanel.put("abnormal_count", abnormalCount);
                microbePanel.put("normal_count", Math.max(microbeResults.size() - abnormalCount, 0));
                microbePanel.put("results", microbeResults);
                microbePanels.add(microbePanel);
            }
        }
        labResults.put("microbe_panels", microbePanels);

        return labResults;
    }

    private Map<String, Object> buildCompactLabResultItem(PatientCourseData.PatTestResult result) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (result == null) {
            return item;
        }
        String name = firstNonBlank(result.getItemname(), result.getEngname());
        String value = result.getResultdesc();
        String flag = firstNonBlank(result.getState(), result.getAllJyFlag());
        String ref = result.getRefdesc();
        if (!StringUtils.hasText(name)
                && !StringUtils.hasText(value)
                && !StringUtils.hasText(flag)
                && !StringUtils.hasText(ref)) {
            return item;
        }
        if (StringUtils.hasText(name)) {
            item.put("name", name);
        }
        if (StringUtils.hasText(value)) {
            item.put("value", value);
        }
        if (StringUtils.hasText(result.getUnit())) {
            item.put("unit", result.getUnit());
        }
        if (StringUtils.hasText(flag)) {
            item.put("flag", flag);
        }
        if (StringUtils.hasText(ref)) {
            item.put("ref", ref);
        }
        item.put("is_abnormal", isAbnormalFlag(flag));
        return item;
    }

    private Map<String, Object> buildCompactMicrobeResultItem(PatientCourseData.MicrobeInfo microbeInfo) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (microbeInfo == null) {
            return item;
        }
        String organism = microbeInfo.getDataCode();
        String result = joinNonBlankDistinct(" / ",
                microbeInfo.getResult(),
                microbeInfo.getResult1(),
                microbeInfo.getResult2());
        List<String> drugSensitivityList = buildDrugSensitivityList(microbeInfo.getAntiDrugList());
        boolean abnormal = isMicrobeResultAbnormal(result, drugSensitivityList);
        if (!StringUtils.hasText(organism) && !StringUtils.hasText(result) && drugSensitivityList.isEmpty()) {
            return item;
        }
        if (StringUtils.hasText(organism)) {
            item.put("organism", organism);
        }
        if (StringUtils.hasText(result)) {
            item.put("result", result);
        }
        if (!drugSensitivityList.isEmpty()) {
            item.put("drug_sensitivity", drugSensitivityList);
        }
        item.put("is_abnormal", abnormal);
        if (abnormal) {
            item.put("flag", "异常");
        } else if (StringUtils.hasText(result)) {
            item.put("flag", "正常");
        }
        return item;
    }

    private List<String> buildDrugSensitivityList(List<PatientCourseData.AntiDrugInfo> antiDrugList) {
        List<String> drugSensitivityList = new ArrayList<>();
        for (PatientCourseData.AntiDrugInfo antiDrug : nonNullList(antiDrugList)) {
            String sensitivity = antiDrug.getSensitivity();
            if (!StringUtils.hasText(antiDrug.getDataName()) && !StringUtils.hasText(sensitivity)
                    && !StringUtils.hasText(antiDrug.getMic())) {
                continue;
            }
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
        return drugSensitivityList;
    }

    private String joinNonBlankDistinct(String delimiter, String... values) {
        LinkedHashSet<String> distinctValues = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                distinctValues.add(value.trim());
            }
        }
        return String.join(delimiter, distinctValues);
    }

    private boolean isAbnormalFlag(String flag) {
        if (!StringUtils.hasText(flag)) {
            return false;
        }
        return flag.contains("高")
                || flag.contains("低")
                || flag.contains("↑")
                || flag.contains("↓")
                || flag.contains("阳性")
                || flag.contains("异常")
                || flag.contains("危急")
                || flag.contains("耐药")
                || flag.contains("中介");
    }

    private boolean isMicrobeResultAbnormal(String result, List<String> drugSensitivityList) {
        if (isAbnormalFlag(result)) {
            return true;
        }
        for (String drugSensitivity : nonNullList(drugSensitivityList)) {
            if (isAbnormalFlag(drugSensitivity)) {
                return true;
            }
        }
        return false;
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

    private List<Map<String, Object>> buildUseMedicine(List<PatientCourseData.PatUseMedicine> useMedicineList) {
        List<Map<String, Object>> medications = new ArrayList<>();
        for (PatientCourseData.PatUseMedicine useMedicine : nonNullList(useMedicineList)) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (useMedicine == null) {
                continue;
            }
            if (StringUtils.hasText(useMedicine.getMediName())) {
                item.put("medication_name", useMedicine.getMediName());
            }
            if (StringUtils.hasText(useMedicine.getMedCalss())) {
                item.put("category", useMedicine.getMedCalss());
            }
            if (StringUtils.hasText(useMedicine.getMediPath())) {
                item.put("route", useMedicine.getMediPath());
            }
            if (StringUtils.hasText(useMedicine.getMediNum())) {
                item.put("dose", useMedicine.getMediNum());
            }
            if (StringUtils.hasText(useMedicine.getUnit())) {
                item.put("unit", useMedicine.getUnit());
            }
            if (StringUtils.hasText(useMedicine.getFrequency())) {
                item.put("frequency", useMedicine.getFrequency());
            }
            String startTime = firstNonBlank(
                    formatDateTime(useMedicine.getBeginTime(), "yyyy-MM-dd HH:mm"),
                    useMedicine.getZxsj()
            );
            if (StringUtils.hasText(startTime)) {
                item.put("start_time", startTime);
            }
            if (StringUtils.hasText(useMedicine.getEndTime())) {
                item.put("end_time", useMedicine.getEndTime());
            }
            if (StringUtils.hasText(useMedicine.getMediAim())) {
                item.put("purpose", useMedicine.getMediAim());
            }
            if (StringUtils.hasText(useMedicine.getDocadvtype())) {
                item.put("order_type", useMedicine.getDocadvtype());
            }
            if (!item.isEmpty()) {
                medications.add(item);
            }
        }
        return medications;
    }

    private List<Map<String, Object>> buildTransfers(List<PatientCourseData.PatTransfer> transferList) {
        List<Map<String, Object>> transfers = new ArrayList<>();
        for (PatientCourseData.PatTransfer transfer : nonNullList(transferList)) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (transfer == null) {
                continue;
            }
            String transferTime = formatDateTime(transfer.getIndeptdate(), "yyyy-MM-dd HH:mm");
            if (StringUtils.hasText(transferTime)) {
                item.put("transfer_time", transferTime);
            }
            if (StringUtils.hasText(transfer.getOutdeptname())) {
                item.put("from_department", transfer.getOutdeptname());
            }
            if (StringUtils.hasText(transfer.getIndeptname())) {
                item.put("to_department", transfer.getIndeptname());
            }
            if (!item.isEmpty()) {
                transfers.add(item);
            }
        }
        return transfers;
    }

    private List<Map<String, Object>> buildOperations(List<PatientCourseData.PatOpsCutInfor> opsList) {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (PatientCourseData.PatOpsCutInfor ops : nonNullList(opsList)) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (ops == null) {
                continue;
            }
            if (StringUtils.hasText(ops.getOpsName())) {
                item.put("operation_name", ops.getOpsName());
            }
            String operationTime = formatDateTime(ops.getBegTime(), "yyyy-MM-dd HH:mm");
            if (StringUtils.hasText(operationTime)) {
                item.put("operation_time", operationTime);
            }
            if (StringUtils.hasText(ops.getEndTime())) {
                item.put("operation_end_time", ops.getEndTime());
            }
            if (StringUtils.hasText(ops.getCutType())) {
                item.put("cut_type", ops.getCutType());
            }
            if (StringUtils.hasText(ops.getHocusMode())) {
                item.put("anesthesia_mode", ops.getHocusMode());
            }
            List<Map<String, Object>> preWardMedicines = buildOperationMedicines(ops.getPreWardMedicineList());
            if (!preWardMedicines.isEmpty()) {
                item.put("pre_ward_medicines", preWardMedicines);
            }
            List<Map<String, Object>> perioperativeMedicines = buildOperationMedicines(ops.getPerioperativeMedicineList());
            if (!perioperativeMedicines.isEmpty()) {
                item.put("perioperative_medicines", perioperativeMedicines);
            }
            if (!item.isEmpty()) {
                operations.add(item);
            }
        }
        return operations;
    }

    private List<Map<String, Object>> buildOperationMedicines(List<PatientCourseData.OpsMedicine> medicineList) {
        List<Map<String, Object>> medicines = new ArrayList<>();
        for (PatientCourseData.OpsMedicine medicine : nonNullList(medicineList)) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (medicine == null) {
                continue;
            }
            if (StringUtils.hasText(medicine.getMediName())) {
                item.put("medication_name", medicine.getMediName());
            }
            if (StringUtils.hasText(medicine.getDosage())) {
                item.put("dose", medicine.getDosage());
            }
            String beginTime = formatDateTime(medicine.getBeginTime(), "yyyy-MM-dd HH:mm");
            if (StringUtils.hasText(beginTime)) {
                item.put("start_time", beginTime);
            }
            if (!item.isEmpty()) {
                medicines.add(item);
            }
        }
        return medicines;
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
        return DateTimeUtils.truncateToMillis(time).format(DateTimeFormatter.ofPattern(pattern));
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

    private List<String> normalizeReqnos(List<String> reqnos) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String reqno : nonNullList(reqnos)) {
            if (StringUtils.hasText(reqno)) {
                normalized.add(reqno.trim());
            }
        }
        return new ArrayList<>(normalized);
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
