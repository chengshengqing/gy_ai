package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 院感（医院获得性）可能性等级。
 * 对应 infection_case_snapshot.nosocomial_likelihood 与法官输出的 nosocomialLikelihood。
 */
public enum InfectionNosocomialLikelihood {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    private final String code;
    private final String description;

    InfectionNosocomialLikelihood(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionNosocomialLikelihood fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionNosocomialLikelihood code: " + code));
    }

    public static InfectionNosocomialLikelihood fromCodeOrDefault(String code, InfectionNosocomialLikelihood defaultValue) {
        try {
            return fromCode(code);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public static Set<String> allCodes() {
        return Arrays.stream(values()).map(InfectionNosocomialLikelihood::code).collect(Collectors.toUnmodifiableSet());
    }
}
