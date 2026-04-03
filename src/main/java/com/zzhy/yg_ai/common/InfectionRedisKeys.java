package com.zzhy.yg_ai.common;

import com.zzhy.yg_ai.domain.enums.SummaryContextType;
import java.time.LocalDate;

public final class InfectionRedisKeys {

    private static final String PREFIX = "yg_ai:infection";

    private InfectionRedisKeys() {
    }

    public static String summaryContext(SummaryContextType summaryType,
                                        String reqno,
                                        LocalDate anchorDate,
                                        int windowDays) {
        String safeReqno = reqno == null ? "" : reqno.trim();
        String safeDate = anchorDate == null ? "" : anchorDate.toString();
        return String.join(":",
                PREFIX,
                "summary_context",
                summaryType == null ? "" : summaryType.name(),
                safeReqno,
                safeDate,
                String.valueOf(windowDays));
    }

    public static String patientSummaryContextPattern(String reqno) {
        String safeReqno = reqno == null ? "" : reqno.trim();
        return String.join(":",
                PREFIX,
                "summary_context",
                "*",
                safeReqno,
                "*",
                "*");
    }
}
