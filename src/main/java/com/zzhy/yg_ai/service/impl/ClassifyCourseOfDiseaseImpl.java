package com.zzhy.yg_ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.common.JsonUtil;
import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.MedicalEventData;
import com.zzhy.yg_ai.domain.entity.MedicalEvent;
import com.zzhy.yg_ai.domain.entity.MedicalRecordStructured;
import com.zzhy.yg_ai.domain.entity.PatIllnessCourse;
import com.zzhy.yg_ai.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClassifyCourseOfDiseaseImpl implements ClassifyCourseOfDiseaseService {

    private final IPatIllnessCourseService patIllnessCourseService;

    private final IMedicalRecordStructuredService medicalRecordStructuredService;

    private final AiProcessLogService aiProcessLogService;

    private final IMedicalEventService medicalEventService;

    /**
     * 必须包含的 8 个字段
     */
    private static final List<String> REQUIRED_FIELDS = Arrays.asList(
            "courseTime",
            "symptoms",
            "signsAndExaminations",
            "doctorAssessment",
            "surgeryRecords",
            "treatmentPlan",
            "consultationOpinions",
            "unclassified"
    );

    @Override
    public List<PatIllnessCourse> getCourseOfDiseaseList(Integer pageNum, Integer pageSize) {
//        List<PatIllnessCourse> pageByClassifyCourse = patIllnessCourseService.getPageByClassifyCourse(pageNum, pageSize);
        return patIllnessCourseService.getPageByClassifyCourse(pageNum, pageSize);
//        return patIllnessCourseService.getPageByClassifyCourse(pageNum, pageSize)
//                .stream()
//                .map(PatIllnessCourse::getIllnessContent)
//                .toList();
    }

    @Override
    public BaseResult saveStructureData(String structText, PatIllnessCourse patIllnessCourse) {
        BaseResult result = new BaseResult<>();
        // 创建结构化记录
        MedicalRecordStructured record = new MedicalRecordStructured();
        record.setRecordId(Long.valueOf(patIllnessCourse.getId()));
        record.setReqno(patIllnessCourse.getReqno());
        record.setPathosid(patIllnessCourse.getPathosid());
        try {
            //解析 AI 返回的 JSON 结果
            JsonNode jsonNode = JsonUtil.toJsonNode(structText);

            // 校验所有必需字段是否存在
            Set<String> missingFields = new HashSet<>();
            for (String fieldName : REQUIRED_FIELDS) {
                if (!jsonNode.has(fieldName)) {
                    missingFields.add(fieldName);
                }
            }

            // 如果有缺失的字段，记录错误日志并抛出异常
            if (!missingFields.isEmpty()) {
                String errorMessage = "AI 返回数据缺失以下字段：" + String.join(", ", missingFields);

                // 记录失败日志
                aiProcessLogService.logFailed(
                        record.getReqno(),
                        record.getPathosid(),
                        record.getRecordId(),
                        "STRUCT_CLASSIFICATION",
                        structText,
                        "MISSING_FIELDS",
                        errorMessage,
                        "{\"missingFields\": \"" + String.join(", ", missingFields) + "\"}"
                );

//                throw new RuntimeException(errorMessage);
                result.setCode(600);
                result.setMessage(errorMessage);
                return result;
            }

            // 提取 courseTime（病程记录时间）
            if (jsonNode.has("courseTime")) {
                record.setCourseTime(jsonNode.get("courseTime").toString());
            }
            if (jsonNode.has("symptoms")) {
                record.setSymptoms(jsonNode.get("symptoms").toString());
            }
            if (jsonNode.has("signsAndExaminations")) {
                record.setSignsAndExaminations(jsonNode.get("signsAndExaminations").toString());
            }
            if (jsonNode.has("doctorAssessment")) {
                record.setDoctorAssessment(jsonNode.get("doctorAssessment").toString());
            }
            if (jsonNode.has("surgeryRecords")) {
                record.setSurgeryRecords(jsonNode.get("surgeryRecords").toString());
            }
            if (jsonNode.has("treatmentPlan")) {
                record.setTreatmentPlan(jsonNode.get("treatmentPlan").toString());
            }
            if (jsonNode.has("consultationOpinions")) {
                record.setConsultationOpinions(jsonNode.get("consultationOpinions").toString());
            }
            if (jsonNode.has("unclassified")) {
                record.setUnclassified(jsonNode.get("unclassified").toString());
            }

            medicalRecordStructuredService.save(record);

            // 记录成功日志
            aiProcessLogService.logSuccess(
                    record.getReqno(),
                    record.getPathosid(),
                    record.getRecordId(),
                    "STRUCT_CLASSIFICATION",
                    structText,
                    "{\"recordId\": \"" + record.getId() + "\"}"
            );
            result.setCode(200);
            result.setMessage("成功");
            return result;

        } catch (JsonUtil.JsonException e) {
            // JSON 解析失败，记录错误日志
            aiProcessLogService.logFailed(
                    record.getReqno(),
                    record.getPathosid(),
                    record.getRecordId(),
                    "STRUCT_CLASSIFICATION",
                    structText,
                    "JSON_PARSE_ERROR",
                    "JSON 解析失败：" + e.getMessage(),
                    null
            );
//            throw new RuntimeException("JSON 解析失败：" + e.getMessage(), e);
            result.setCode(600);
            result.setMessage("JSON 解析失败：" + e.getMessage());
            return result;

        } catch (RuntimeException e) {
            // 其他运行时异常（如字段缺失），直接抛出
            throw e;
//            result.setCode(600);
//            result.setMessage("其他运行时异常：" + e.getMessage());
//            return result;
        } catch (Exception e) {
            // 未知异常，记录错误日志
            aiProcessLogService.logFailed(
                    record.getReqno(),
                    record.getPathosid(),
                    record.getRecordId(),
                    "STRUCT_CLASSIFICATION",
                    structText,
                    "UNKNOWN_ERROR",
                    "未知错误：" + e.getMessage(),
                    null
            );
//            throw new RuntimeException("处理 AI 响应时发生错误：" + e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public List<MedicalRecordStructured> getStructureCourseList(Integer pageNum, Integer pageSize) {
        return medicalRecordStructuredService.getStructureCourseList(pageNum, pageSize);
    }

    @Override
    public BaseResult saveEventData(String eventData, String structuredColumn, MedicalRecordStructured record, String resourceText) {
        BaseResult result = new BaseResult<>();
        
        // 创建医疗事件列表
        List<MedicalEvent> eventList = new ArrayList<>();
        
        try {
            // 1. JSON 解析 eventData，判断是否为正确的 json 格式
            JsonNode jsonNode = JsonUtil.toJsonNode(eventData);
            
            // 2. 解析成功后，批量保存到 medicalEvent 中
            // 判断是数组还是单个对象
            if (jsonNode.isArray()) {
                // 数组形式
                for (JsonNode itemNode : jsonNode) {
                    MedicalEvent event = parseAndCreateEvent(itemNode.toString(), record);
                    if (event != null) {
                        eventList.add(event);
                    }
                }
            } else {
                // 单个对象形式
                MedicalEvent event = parseAndCreateEvent(eventData, record);
                if (event != null) {
                    eventList.add(event);
                }
            }

            eventList = eventList.stream().peek(event -> {
                event.setMedicalRecordStructuredColumn(structuredColumn);
                event.setMedicalRecordStructuredId(record.getId());
            }).toList();

            // 批量保存
            if (!eventList.isEmpty()) {
                medicalEventService.saveBatch(eventList);
                
                // 记录成功日志
                aiProcessLogService.logSuccess(
                        record.getReqno(),
                        record.getPathosid(),
                        record.getId(),
                        "EVENT_EXTRACTION",
                        eventData,
                        "{\"savedCount\": \"" + eventList.size() + "\"}"
                );
                

            } else {
                // 没有有效数据
                String errorMessage = "解析后未获取到有效的事件数据";

                MedicalEvent event = parseAndCreateEvent("{}", record);
                event.setMedicalRecordStructuredColumn(structuredColumn);
                event.setMedicalRecordStructuredId(record.getId());
                event.setEventName(errorMessage);
                event.setEventType("unclassified");
                event.setSourceText(resourceText);
                medicalEventService.save(event);

                ObjectNode objectNode = JsonUtil.createObjectNode();
                objectNode.put("saved", errorMessage);

                aiProcessLogService.logSuccess(
                        record.getReqno(),
                        record.getPathosid(),
                        record.getId(),
                        "EVENT_EXTRACTION",
                        objectNode.toPrettyString(),
                        resourceText
                );
                /*aiProcessLogService.logFailed(
                        record.getReqno(),
                        record.getPathosid(),
                        record.getId(),
                        "EVENT_EXTRACTION",
                        eventData,
                        "NO_VALID_DATA",
                        errorMessage,
                        null
                );*/
            }
            result.setCode(200);
            result.setMessage("成功保存 " + eventList.size() + " 条事件数据");
            
            return result;
            
        } catch (JsonUtil.JsonException e) {
            // JSON 解析失败，记录错误日志
            aiProcessLogService.logFailed(
                    record.getReqno(),
                    record.getPathosid(),
                    record.getId(),
                    "EVENT_EXTRACTION",
                    eventData,
                    "JSON_PARSE_ERROR",
                    "JSON 解析失败：" + e.getMessage(),
                    null
            );
            
            result.setCode(600);
            result.setMessage("JSON 解析失败：" + e.getMessage());
            return result;
            
        } catch (Exception e) {
            // 未知异常，记录错误日志
            aiProcessLogService.logFailed(
                    record.getReqno(),
                    record.getPathosid(),
                    record.getId(),
                    "EVENT_EXTRACTION",
                    eventData,
                    "UNKNOWN_ERROR",
                    "处理事件数据时发生错误：" + e.getMessage(),
                    null
            );
            throw e;
        }
    }
    
    /**
     * 解析单个事件数据并创建 MedicalEvent 对象
     *
     * @param eventData 单个事件的 JSON 字符串
     * @param record 关联的结构化记录
     * @return MedicalEvent 对象，如果解析失败则返回 null
     */
    private MedicalEvent parseAndCreateEvent(String eventData, MedicalRecordStructured record) {
        try {
            // 使用 JsonUtil 将 JSON 转换为 MedicalEventData 对象
            MedicalEventData eventDTO = JsonUtil.fromJson(eventData, MedicalEventData.class);
            
            // 创建医疗事件实体
            MedicalEvent event = new MedicalEvent();
            event.setMedicalRecordStructuredId(record.getId());
            event.setRecordId(record.getRecordId());
            event.setReqno(record.getReqno());
            event.setPathosid(record.getPathosid());
            event.setEventType(eventDTO.getEventType());
            event.setEventName(eventDTO.getEventName());
            event.setEventTimeRaw(eventDTO.getEventTimeRaw());
            event.setAttributes(eventDTO.getAttributes());
            event.setNegation(eventDTO.getNegation() != null ? eventDTO.getNegation().toString() : "false");
            event.setSourceText(eventDTO.getSourceText());
            event.init();
            
            return event;
            
        } catch (Exception e) {
            // 单个事件解析失败，记录警告（不阻断其他事件的解析）
            log.warn("解析单个事件数据失败：" + e.getMessage() + ", 数据：" + eventData);
            return null;
        }
    }
}
