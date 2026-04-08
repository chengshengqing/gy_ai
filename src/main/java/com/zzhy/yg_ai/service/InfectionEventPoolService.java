package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import java.util.List;

public interface InfectionEventPoolService extends IService<InfectionEventPoolEntity> {

    InfectionEventPoolEntity createEvent(InfectionEventPoolEntity entity);

    InfectionEventPoolEntity saveOrUpdateByEventKey(InfectionEventPoolEntity entity);

    InfectionEventPoolEntity saveNormalizedEvent(NormalizedInfectionEvent event);

    List<InfectionEventPoolEntity> saveNormalizedEvents(List<NormalizedInfectionEvent> events);

    List<InfectionEventPoolEntity> listActiveEvents(String reqno);

    Long getLatestActiveEventVersion(String reqno);
}
