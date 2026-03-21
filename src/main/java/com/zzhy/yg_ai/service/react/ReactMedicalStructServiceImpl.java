package com.zzhy.yg_ai.service.react;

import com.zzhy.yg_ai.ai.reactAgent.AgentScopeMedicalStructProperties;
import com.zzhy.yg_ai.ai.reactAgent.AgentScopeMedicalStructRunner;
import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.entity.MedicalRecordStructured;
import com.zzhy.yg_ai.domain.entity.PatIllnessCourse;
import com.zzhy.yg_ai.service.ClassifyCourseOfDiseaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReactMedicalStructServiceImpl implements ReactMedicalStructService {

    private final ClassifyCourseOfDiseaseService classifyCourseOfDiseaseService;
    private final AgentScopeMedicalStructRunner medicalStructRunner;
    private final AgentScopeMedicalStructProperties properties;

    @Override
    public void executeClassifyData() {
        List<PatIllnessCourse> courseList = classifyCourseOfDiseaseService.getCourseOfDiseaseList(1, properties.getClassifyBatchSize());
        for (PatIllnessCourse course : courseList) {
            if (StringUtils.isBlank(course.getIllnessContent())) {
                log.warn("病程内容为空，跳过一级分类，recordId={}, reqno={}", course.getId(), course.getReqno());
                continue;
            }
            String structuredJson = medicalStructRunner.classify(course.getIllnessContent());
            BaseResult<?> result = classifyCourseOfDiseaseService.saveStructureData(structuredJson, course);
            if (!result.isSuccess()) {
                log.warn("病程一级分类保存失败，recordId={}, reqno={}, message={}", course.getId(), course.getReqno(), result.getMessage());
            }
        }
    }

    @Override
    public void executeMedicalEvent() {
        List<MedicalRecordStructured> records = classifyCourseOfDiseaseService.getStructureCourseList(1, properties.getStructuredBatchSize());
        for (MedicalRecordStructured record : records) {
            if (StringUtils.isNotBlank(record.getSymptoms()) && !"[]".equals(record.getSymptoms())) {
                extractAndSave(record.getSymptoms(), "symptoms", record, medicalStructRunner.extractSymptomEvents(record.getSymptoms()));
            }
            String examSource = defaultString(record.getSignsAndExaminations()) + defaultString(record.getDoctorAssessment());
            if (StringUtils.isNotBlank(examSource)) {
                extractAndSave(examSource, "signsAndExaminations", record, medicalStructRunner.extractExamEvents(examSource));
                extractAndSave(examSource, "signsAndExaminations", record, medicalStructRunner.extractImagingEvents(examSource));
            }
            String procedureSource = defaultString(record.getSurgeryRecords()) + defaultString(record.getTreatmentPlan());
            if (StringUtils.isNotBlank(procedureSource)) {
                extractAndSave(procedureSource, "surgeryRecords,treatmentPlan", record, medicalStructRunner.extractProcedureEvents(procedureSource));
            }
            if (StringUtils.isNotBlank(record.getSurgeryRecords()) && !"[]".equals(record.getSurgeryRecords())) {
                extractAndSave(record.getSurgeryRecords(), "surgeryRecords", record, medicalStructRunner.extractSurgeryEvents(record.getSurgeryRecords()));
            }
            String medicationSource = defaultString(record.getTreatmentPlan()) + defaultString(record.getDoctorAssessment());
            if (StringUtils.isNotBlank(medicationSource)) {
                extractAndSave(medicationSource, "treatmentPlan", record, medicalStructRunner.extractMedicationEvents(medicationSource));
            }
            String transferSource = defaultString(record.getTreatmentPlan()) + defaultString(record.getConsultationOpinions());
            if (StringUtils.isNotBlank(transferSource)) {
                extractAndSave(transferSource, "treatmentPlan,consultationOpinions", record, medicalStructRunner.extractTransferEvents(transferSource));
            }
            if (StringUtils.isNotBlank(record.getUnclassified()) && !"[]".equals(record.getUnclassified())) {
                classifyCourseOfDiseaseService.saveEventData("[]", "unclassified", record, record.getUnclassified());
            }
        }
    }

    @Override
    public void executeMedicalStructured() {
        log.info("当前阶段仅完成 AgentScope 一级分类与二级事件抽取，病程格式化工作流待后续扩展。");
    }

    private void extractAndSave(String sourceText, String structuredColumn, MedicalRecordStructured record, String eventJson) {
        if (StringUtils.isBlank(sourceText) || "[]".equals(sourceText)) {
            return;
        }
        BaseResult<?> result = classifyCourseOfDiseaseService.saveEventData(eventJson, structuredColumn, record, sourceText);
        if (!result.isSuccess()) {
            log.warn("事件抽取保存失败，structuredId={}, column={}, message={}", record.getId(), structuredColumn, result.getMessage());
        }
    }

    private String defaultString(String text) {
        if (StringUtils.isBlank(text) || "[]".equals(text)) {
            return "";
        }
        return text;
    }
}
