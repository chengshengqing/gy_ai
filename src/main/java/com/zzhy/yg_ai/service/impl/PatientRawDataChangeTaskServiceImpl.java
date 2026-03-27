package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.mapper.PatientRawDataChangeTaskMapper;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PatientRawDataChangeTaskServiceImpl
        extends ServiceImpl<PatientRawDataChangeTaskMapper, PatientRawDataChangeTaskEntity>
        implements PatientRawDataChangeTaskService {

    @Override
    public void appendChange(Long patientRawDataId, String reqno, LocalDateTime rawDataLastTime) {
        if (patientRawDataId == null || !StringUtils.hasText(reqno) || rawDataLastTime == null) {
            return;
        }
        PatientRawDataChangeTaskEntity task = new PatientRawDataChangeTaskEntity();
        task.setPatientRawDataId(patientRawDataId);
        task.setReqno(reqno.trim());
        task.setRawDataLastTime(rawDataLastTime);
        task.setCreateTime(LocalDateTime.now());
        this.save(task);
    }

    @Override
    public void appendChanges(List<PatientRawDataChangeTaskEntity> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        List<PatientRawDataChangeTaskEntity> validTasks = new ArrayList<>();
        for (PatientRawDataChangeTaskEntity task : tasks) {
            if (task == null
                    || task.getPatientRawDataId() == null
                    || !StringUtils.hasText(task.getReqno())
                    || task.getRawDataLastTime() == null) {
                continue;
            }
            if (task.getCreateTime() == null) {
                task.setCreateTime(LocalDateTime.now());
            }
            validTasks.add(task);
        }
        if (!validTasks.isEmpty()) {
            this.saveBatch(validTasks);
        }
    }
}
