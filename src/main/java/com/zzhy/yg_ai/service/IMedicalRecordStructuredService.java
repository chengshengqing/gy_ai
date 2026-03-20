package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.MedicalRecordStructured;

import java.util.List;

/**
 * 结构化病历服务接口
 */
public interface IMedicalRecordStructuredService extends IService<MedicalRecordStructured> {

    boolean save(MedicalRecordStructured record);

    List<MedicalRecordStructured> getStructureCourseList(Integer pageNum, Integer pageSize);
}
