package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.entity.MedicalRecordStructured;
import com.zzhy.yg_ai.mapper.MedicalRecordStructuredMapper;
import com.zzhy.yg_ai.service.AiProcessLogService;
import com.zzhy.yg_ai.service.IMedicalRecordStructuredService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 结构化病历服务实现类
 */
@Service
public class MedicalRecordStructuredServiceImpl extends ServiceImpl<MedicalRecordStructuredMapper, MedicalRecordStructured> implements IMedicalRecordStructuredService {

    @Autowired
    private AiProcessLogService aiProcessLogService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean save(MedicalRecordStructured record) {
        // 设置创建时间
        record.init();

        // 保存到数据库 - 调用父类的方法，避免递归调用
        return super.save(record);
    }

    @Override
    public List<MedicalRecordStructured> getStructureCourseList(Integer pageNum, Integer pageSize) {
        IPage page = new Page<>(pageNum, pageSize);
        return baseMapper.getPageByStructureCourse(page);
    }
}
