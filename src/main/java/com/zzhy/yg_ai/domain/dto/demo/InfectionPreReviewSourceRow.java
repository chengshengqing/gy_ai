package com.zzhy.yg_ai.domain.dto.demo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class InfectionPreReviewSourceRow {

    private String reqno;
    private LocalDateTime lastJudgeTime;
    private String primarySite;
    private String nosocomialLikelihood;
    private String resultJson;
}
