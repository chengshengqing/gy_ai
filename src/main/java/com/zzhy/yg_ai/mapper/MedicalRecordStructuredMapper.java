package com.zzhy.yg_ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zzhy.yg_ai.domain.entity.MedicalRecordStructured;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 结构化病历 Mapper 接口
 */
@Mapper
public interface MedicalRecordStructuredMapper extends BaseMapper<MedicalRecordStructured> {

    List<MedicalRecordStructured> getPageByStructureCourse(IPage page);
}
