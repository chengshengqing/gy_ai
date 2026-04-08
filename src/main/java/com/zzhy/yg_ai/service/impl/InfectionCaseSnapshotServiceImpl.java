package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.InfectionCaseSnapshotEntity;
import com.zzhy.yg_ai.mapper.InfectionCaseSnapshotMapper;
import com.zzhy.yg_ai.service.InfectionCaseSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class InfectionCaseSnapshotServiceImpl
        extends ServiceImpl<InfectionCaseSnapshotMapper, InfectionCaseSnapshotEntity>
        implements InfectionCaseSnapshotService {

    @Override
    public InfectionCaseSnapshotEntity getByReqno(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return null;
        }
        return this.lambdaQuery()
                .eq(InfectionCaseSnapshotEntity::getReqno, reqno.trim())
                .one();
    }

    @Override
    public InfectionCaseSnapshotEntity getOrInit(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return null;
        }
        InfectionCaseSnapshotEntity existing = getByReqno(reqno);
        if (existing != null) {
            return existing;
        }
        InfectionCaseSnapshotEntity snapshot = new InfectionCaseSnapshotEntity();
        snapshot.setReqno(reqno.trim());
        snapshot.initForCreate();
        this.save(snapshot);
        return snapshot;
    }

    @Override
    public void saveOrUpdateSnapshot(InfectionCaseSnapshotEntity snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.getId() == null) {
            snapshot.initForCreate();
            this.save(snapshot);
            return;
        }
        snapshot.touch();
        this.updateById(snapshot);
    }
}
