package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionCertainty {
    CONFIRMED("confirmed", "明确"),
    SUSPECTED("suspected", "疑似"),
    POSSIBLE("possible", "可能"),
    WORKUP_NEEDED("workup_needed", "待排待查"),
    RISK_ONLY("risk_only", "仅风险提示");

    private final String code;
    private final String description;

    InfectionCertainty(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionCertainty fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionCertainty code: " + code));
    }
}
