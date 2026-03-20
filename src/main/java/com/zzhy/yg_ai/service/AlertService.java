package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.entity.InfectionAlertEntity;
import com.zzhy.yg_ai.domain.entity.InfectionReviewEntity;
import java.time.LocalDateTime;

public interface AlertService {

    void saveAlert(InfectionAlertEntity alert);

    void saveReview(InfectionReviewEntity review);

    boolean existsAlertInWindow(String reqno,
                                String riskLevel,
                                String infectionType,
                                LocalDateTime startTime,
                                LocalDateTime endTime);
}
