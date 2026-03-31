package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionPolarity {
    POSITIVE("positive", "阳性"),
    NEGATIVE("negative", "阴性"),
    NEUTRAL("neutral", "中性"),
    UNCERTAIN("uncertain", "未定");

    private final String code;
    private final String description;

    InfectionPolarity(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionPolarity fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionPolarity code: " + code));
    }
}
