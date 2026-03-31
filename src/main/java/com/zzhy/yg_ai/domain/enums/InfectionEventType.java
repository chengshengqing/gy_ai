package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionEventType {
    DIAGNOSIS("diagnosis", "诊断"),
    VITAL_SIGN("vital_sign", "生命体征"),
    LAB_PANEL("lab_panel", "检验套餐"),
    LAB_RESULT("lab_result", "检验结果"),
    MICROBIOLOGY("microbiology", "微生物"),
    IMAGING("imaging", "影像"),
    ORDER("order", "医嘱"),
    DEVICE("device", "器械"),
    PROCEDURE("procedure", "操作"),
    NOTE("note", "文书原文"),
    ASSESSMENT("assessment", "临床判断"),
    CONSULT("consult", "会诊意见"),
    PROBLEM("problem", "问题列表");

    private final String code;
    private final String description;

    InfectionEventType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionEventType fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionEventType code: " + code));
    }
}
