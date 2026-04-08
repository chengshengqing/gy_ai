package com.zzhy.yg_ai.common;

import com.zzhy.yg_ai.domain.enums.SummaryContextType;
public final class InfectionRedisKeys {

    private static final String PREFIX = "yg_ai:infection";

    private InfectionRedisKeys() {
    }

    public static String patientSummaryContext(SummaryContextType summaryType, String reqno) {
        String safeReqno = reqno == null ? "" : reqno.trim();
        return String.join(":",
                PREFIX,
                "summary_context",
                summaryType == null ? "" : summaryType.name(),
                safeReqno,
                "latest");
    }

    public static String patientSummaryContextPattern(String reqno) {
        String safeReqno = reqno == null ? "" : reqno.trim();
        return String.join(":",
                PREFIX,
                "summary_context",
                "*",
                safeReqno,
                "*");
    }
}
