package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import java.time.LocalDateTime;
import java.util.List;

public interface PatientRawDataChangeTaskService extends IService<PatientRawDataChangeTaskEntity> {

    void appendChange(Long patientRawDataId, String reqno, LocalDateTime rawDataLastTime);

    void appendChanges(List<PatientRawDataChangeTaskEntity> tasks);
}
