package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import java.time.LocalDateTime;

public interface InfectionEvidencePacketBuilder {

    InfectionEvidencePacket build(String reqno, LocalDateTime judgeTime);
}
