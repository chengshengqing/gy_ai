package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.InfectionAlertResultEntity;
import com.zzhy.yg_ai.mapper.InfectionAlertResultMapper;
import com.zzhy.yg_ai.service.InfectionAlertResultService;
import org.springframework.stereotype.Service;

@Service
public class InfectionAlertResultServiceImpl
        extends ServiceImpl<InfectionAlertResultMapper, InfectionAlertResultEntity>
        implements InfectionAlertResultService {

    @Override
    public InfectionAlertResultEntity saveResult(InfectionAlertResultEntity entity) {
        if (entity == null) {
            return null;
        }
        entity.initForCreate();
        this.save(entity);
        return entity;
    }
}
