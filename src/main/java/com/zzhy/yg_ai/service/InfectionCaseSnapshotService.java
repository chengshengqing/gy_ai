package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.InfectionCaseSnapshotEntity;

public interface InfectionCaseSnapshotService extends IService<InfectionCaseSnapshotEntity> {

    InfectionCaseSnapshotEntity getByReqno(String reqno);

    InfectionCaseSnapshotEntity getOrInit(String reqno);

    void saveOrUpdateSnapshot(InfectionCaseSnapshotEntity snapshot);
}
