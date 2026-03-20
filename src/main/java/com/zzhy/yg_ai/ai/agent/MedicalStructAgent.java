package com.zzhy.yg_ai.ai.agent;

import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.entity.MedicalRecordStructured;
import com.zzhy.yg_ai.domain.entity.PatIllnessCourse;
import com.zzhy.yg_ai.ai.prompt.PromptTemplateManager;
import com.zzhy.yg_ai.service.ClassifyCourseOfDiseaseService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MedicalStructAgent extends AbstractAgent
        implements Agent<String, String> {

    private final PromptTemplateManager promptManager;

    private final ClassifyCourseOfDiseaseService classifyCourseOfDisease;

    public String run() {
        String text = null;
        String prompt = promptManager.buildStructPrompt(text);
        return callModel(prompt);
    }

    /*public static ReactAgent structuredAgent() {
        //创建Agent
        ReactAgent agent = ReactAgent.builder()
                .name("my_agent")
                .tools(getToolCallback())
                .model(chatModel)
                .outputSchema(format)
                .saver(redisSaver)
                .interceptors(new DynamicPromptInterceptor())
                .build();
        return agent;
    }*/

    public void handerClassifyData() {
        List<PatIllnessCourse> courseOfDiseaseList = getCourseOfDisease();
        for (PatIllnessCourse PatIllnessCourse : courseOfDiseaseList) {
            String prompt = promptManager.buildStructPrompt(PatIllnessCourse.getIllnessContent());

            for (int i = 0; i < 3; i++) {
                String structureData = callModel(prompt);
                BaseResult result = classifyCourseOfDisease.saveStructureData(structureData, PatIllnessCourse);
                if (result.isSuccess()) {
                    break;
                }
            }

        }
    }

    public void handerMedicalEvent() {
        /*已存在未解决问题。1.因为提取出错，evnet中只保存了部分事件，因为存在MedicalRecordStructuredId，后续事件都未提取*/
        List<MedicalRecordStructured> structureCourseList = getStructureCourseList();
        for (MedicalRecordStructured record : structureCourseList) {
            handerMedicalEventOfPrompt(record);
        }
    }

    /**
     * 整体处理,对抽取的事件进行格式化
     */
    public void handerMedicalStructured() {
        /*1.从medicalEvent中取出事件，按照record_id分组，每次取10组数据。2.*/
    }

    /**
     * 分页获取原始病程信息
     *
     * @return 分页结果
     */
    public List<PatIllnessCourse> getCourseOfDisease() {
        Integer pageNum = 1;
        Integer pageSize = 1;
        List<PatIllnessCourse> courseOfDiseaseList = classifyCourseOfDisease.getCourseOfDiseaseList(pageNum, pageSize);
        return courseOfDiseaseList;
    }

    /**
     * 分页获取一级分类病程信息
     *
     * @return 分页结果
     */
    public List<MedicalRecordStructured> getStructureCourseList() {
        Integer pageNum = 1;
        Integer pageSize = 10;
        List<MedicalRecordStructured> medicalRecordStructuredList = classifyCourseOfDisease.getStructureCourseList(pageNum, pageSize);
        return medicalRecordStructuredList;
    }

    public void handerMedicalEventOfPrompt(MedicalRecordStructured record) {
        String unclassified = record.getUnclassified();
        String symptomPrompt = null;
        String examPrompt = null;
        String imagePrompt = null;
        String procedurePrompt = null;
        String surgeryPrompt = null;
        String medicationPrompt = null;
        String transferPrompt = null;

        if (StringUtils.isNotBlank(record.getSymptoms()) && !"[]".equals(record.getSymptoms())) {
            symptomPrompt = promptManager.buildSymptomPrompt(record.getSymptoms() + record.getDoctorAssessment());
            for (int i = 0; i < 3; i++) {
                String EventData = callModel(symptomPrompt);
                BaseResult result = classifyCourseOfDisease.saveEventData(EventData, "symptoms", record, record.getSymptoms() + record.getDoctorAssessment());
                if (result.isSuccess()) {
                    break;
                }
            }
        }
        if ((StringUtils.isNotBlank(record.getSignsAndExaminations()) && !"[]".equals(record.getSignsAndExaminations())) ||
                StringUtils.isNotBlank(record.getDoctorAssessment()) && !"[]".equals(record.getDoctorAssessment())
        ) {
            examPrompt = promptManager.buildExamPrompt(record.getSignsAndExaminations() + record.getDoctorAssessment());
            imagePrompt = promptManager.buildImagePrompt(record.getSignsAndExaminations() + record.getDoctorAssessment());
            for (int i = 0; i < 3; i++) {
                String EventData = callModel(examPrompt);
                BaseResult result = classifyCourseOfDisease.saveEventData(EventData, "signsAndExaminations", record, record.getSignsAndExaminations() + record.getDoctorAssessment());
                if (result.isSuccess()) {
                    break;
                }
            }
            for (int i = 0; i < 3; i++) {
                String EventData = callModel(imagePrompt);
                BaseResult result = classifyCourseOfDisease.saveEventData(EventData, "signsAndExaminations", record, record.getSignsAndExaminations() + record.getDoctorAssessment());
                if (result.isSuccess()) {
                    break;
                }
            }

        }
        if (StringUtils.isNotBlank(record.getDoctorAssessment()) && !"[]".equals(record.getDoctorAssessment())) {

        }
        if (StringUtils.isNotBlank(record.getSurgeryRecords()) && !"[]".equals(record.getSurgeryRecords())) {
            procedurePrompt = promptManager.buildProcedurePrompt(record.getSurgeryRecords() + record.getTreatmentPlan());
            surgeryPrompt = promptManager.buildSurgeryPrompt(record.getSurgeryRecords());

            for (int i = 0; i < 3; i++) {
                String EventData = callModel(procedurePrompt);
                BaseResult result = classifyCourseOfDisease.saveEventData(EventData, "surgeryRecords,treatmentPlan", record, record.getSurgeryRecords() + record.getTreatmentPlan());
                if (result.isSuccess()) {
                    break;
                }
            }
            for (int i = 0; i < 3; i++) {
                String EventData = callModel(surgeryPrompt);
                BaseResult result = classifyCourseOfDisease.saveEventData(EventData, "surgeryRecords", record, record.getSurgeryRecords());
                if (result.isSuccess()) {
                    break;
                }
            }

        }
        if ((StringUtils.isNotBlank(record.getTreatmentPlan()) && !"[]".equals(record.getTreatmentPlan())) ||
                StringUtils.isNotBlank(record.getDoctorAssessment()) && !"[]".equals(record.getDoctorAssessment())
        ) {
            medicationPrompt = promptManager.buildMedicationPrompt(record.getTreatmentPlan() + record.getDoctorAssessment());
            for (int i = 0; i < 3; i++) {
                String EventData = callModel(medicationPrompt);
                BaseResult result = classifyCourseOfDisease.saveEventData(EventData, "treatmentPlan", record, record.getTreatmentPlan() + record.getDoctorAssessment());
                if (result.isSuccess()) {
                    break;
                }
            }
        }

        if ((StringUtils.isNotBlank(record.getTreatmentPlan()) && !"[]".equals(record.getTreatmentPlan()))) {
            transferPrompt = promptManager.buildTransferPrompt(record.getTreatmentPlan() + record.getConsultationOpinions());
            for (int i = 0; i < 3; i++) {
                String EventData = callModel(transferPrompt);
                BaseResult result = classifyCourseOfDisease.saveEventData(EventData, "treatmentPlan,consultationOpinions", record, record.getTreatmentPlan() + record.getConsultationOpinions());
                if (result.isSuccess()) {
                    break;
                }
            }
        }

        if ((StringUtils.isNotBlank(record.getUnclassified()) && !"[]".equals(record.getUnclassified()))) {
            String EventData = "[]";
            BaseResult result = classifyCourseOfDisease.saveEventData(EventData, "unclassified", record, record.getUnclassified());
        }
    }

    /**
     * 将病程文本用大模型进行1级分类
     */
    public String classifyCourseOfDisease() {
        return null;
    }

    /**
     * 用大模型判断1级分类结果中包含哪些事件
     */
    public String extractEventsFromCourseOfDisease() {
        return null;
    }

    /**
     * 将病程文本用大模型进行2级事件抽取
     */
    public String extractEvents() {
        return null;
    }

    /**
     * 将1级分类后的结果存入数据库
     */
    public void storeClassificationResult() {
        return;
    }
    /**
     * 将2级事件抽取后的结果存入数据库
     */
    public void storeExtractionResult() {

    }
}
