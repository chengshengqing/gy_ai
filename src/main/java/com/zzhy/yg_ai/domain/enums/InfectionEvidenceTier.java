package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionEvidenceTier {
    HARD("hard", "硬证据"),
    MODERATE("moderate", "中等证据"),
    WEAK("weak", "弱证据");

    private final String code;
    private final String description;

    InfectionEvidenceTier(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionEvidenceTier fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionEvidenceTier code: " + code));
    }
}
