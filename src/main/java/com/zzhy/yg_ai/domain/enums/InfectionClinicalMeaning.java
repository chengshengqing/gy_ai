package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionClinicalMeaning {
    INFECTION_SUPPORT("infection_support", "支持感染"),
    INFECTION_AGAINST("infection_against", "反对感染"),
    INFECTION_UNCERTAIN("infection_uncertain", "感染未明"),
    SCREENING("screening", "筛查"),
    BASELINE_PROBLEM("baseline_problem", "基线问题");

    private final String code;
    private final String description;

    InfectionClinicalMeaning(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionClinicalMeaning fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionClinicalMeaning code: " + code));
    }

}
