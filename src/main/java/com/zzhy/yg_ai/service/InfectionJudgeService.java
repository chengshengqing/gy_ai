package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.JudgeDecisionResult;
import java.time.LocalDateTime;

public interface InfectionJudgeService {

    JudgeDecisionResult judge(InfectionEvidencePacket packet, LocalDateTime judgeTime);
}
