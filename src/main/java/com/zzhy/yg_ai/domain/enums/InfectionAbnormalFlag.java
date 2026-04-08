package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionAbnormalFlag {
    HIGH("high", "升高"),
    LOW("low", "降低"),
    POSITIVE("positive", "阳性"),
    NEGATIVE("negative", "阴性"),
    ABNORMAL("abnormal", "异常"),
    NORMAL("normal", "正常");

    private final String code;
    private final String description;

    InfectionAbnormalFlag(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionAbnormalFlag fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionAbnormalFlag code: " + code));
    }
}
