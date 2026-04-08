package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionEvidenceRole {
    SUPPORT("support", "支持"),
    AGAINST("against", "反证"),
    RISK_ONLY("risk_only", "仅风险"),
    BACKGROUND("background", "背景");

    private final String code;
    private final String description;

    InfectionEvidenceRole(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionEvidenceRole fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionEvidenceRole code: " + code));
    }
}
