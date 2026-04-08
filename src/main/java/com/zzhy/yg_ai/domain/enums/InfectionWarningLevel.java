package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 院感预警等级。
 * 对应 infection_case_snapshot.warning_level 与法官输出的 warningLevel。
 */
public enum InfectionWarningLevel {
    NONE("none", "无"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    private final String code;
    private final String description;

    InfectionWarningLevel(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionWarningLevel fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionWarningLevel code: " + code));
    }

    public static InfectionWarningLevel fromCodeOrDefault(String code, InfectionWarningLevel defaultValue) {
        try {
            return fromCode(code);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public static Set<String> allCodes() {
        return Arrays.stream(values()).map(InfectionWarningLevel::code).collect(Collectors.toUnmodifiableSet());
    }
}
