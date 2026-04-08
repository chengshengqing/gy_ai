package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.InfectionAlertResultEntity;

public interface InfectionAlertResultService extends IService<InfectionAlertResultEntity> {

    InfectionAlertResultEntity saveResult(InfectionAlertResultEntity entity);
}
