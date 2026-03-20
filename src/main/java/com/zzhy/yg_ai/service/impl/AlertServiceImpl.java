package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzhy.yg_ai.domain.entity.InfectionAlertEntity;
import com.zzhy.yg_ai.domain.entity.InfectionReviewEntity;
import com.zzhy.yg_ai.mapper.InfectionAlertMapper;
import com.zzhy.yg_ai.mapper.InfectionReviewMapper;
import com.zzhy.yg_ai.service.AlertService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlertServiceImpl implements AlertService {

    private final InfectionAlertMapper infectionAlertMapper;
    private final InfectionReviewMapper infectionReviewMapper;

    @Override
    public void saveAlert(InfectionAlertEntity alert) {
        infectionAlertMapper.insert(alert);
    }

    @Override
    public void saveReview(InfectionReviewEntity review) {
        infectionReviewMapper.insert(review);
    }

    @Override
    public boolean existsAlertInWindow(String reqno,
                                       String riskLevel,
                                       String infectionType,
                                       LocalDateTime startTime,
                                       LocalDateTime endTime) {
        Long count = infectionAlertMapper.selectCount(new QueryWrapper<InfectionAlertEntity>()
                .eq("reqno", reqno)
                .eq("risk_level", riskLevel)
                .eq("infection_type", infectionType)
                .ge("alert_time", startTime)
                .lt("alert_time", endTime));
        return count != null && count > 0;
    }
}
