package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionSourceSection {
    DIAGNOSIS("diagnosis", "诊断"),
    VITAL_SIGNS("vital_signs", "生命体征"),
    LAB_RESULTS("lab_results", "检验结果"),
    IMAGING("imaging", "影像"),
    DOCTOR_ORDERS("doctor_orders", "医嘱"),
    USE_MEDICINE("use_medicine", "用药"),
    TRANSFER("transfer", "转科"),
    OPERATION("operation", "手术");

    private final String code;
    private final String description;

    InfectionSourceSection(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionSourceSection fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionSourceSection code: " + code));
    }
}
