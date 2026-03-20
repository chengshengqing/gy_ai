package com.zzhy.yg_ai.domain.dto;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 演示页：按患者 reqno 分组的原始病程数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatientRawDataTimelineGroup {

    private String reqno;
    private List<PatientRawDataEntity> records;
}
