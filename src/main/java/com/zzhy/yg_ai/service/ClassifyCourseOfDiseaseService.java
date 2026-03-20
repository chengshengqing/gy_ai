package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.entity.MedicalRecordStructured;
import com.zzhy.yg_ai.domain.entity.PatIllnessCourse;

import java.util.List;

/**
 * 病程分类服务
 */
public interface ClassifyCourseOfDiseaseService {

    /**
     * 分页查询病程内容（仅返回 illnessContent 字段）
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页数量
     * @return 分页结果，records 中仅包含 illnessContent 文本
     */
    List<PatIllnessCourse> getCourseOfDiseaseList(Integer pageNum, Integer pageSize);

    BaseResult saveStructureData(String result, PatIllnessCourse PatIllnessCourse);

    List<MedicalRecordStructured> getStructureCourseList(Integer pageNum, Integer pageSize);

    BaseResult saveEventData(String eventData, String structuredColumn, MedicalRecordStructured record, String resourceText);
}
