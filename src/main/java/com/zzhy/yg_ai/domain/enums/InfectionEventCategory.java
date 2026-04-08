package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionEventCategory {
    FACT("fact", "结构化事实"),
    TEXT("text", "临床文本"),
    SEMANTIC("semantic", "中间语义"),
    CONTEXT("context", "上下文");

    private final String code;
    private final String description;

    InfectionEventCategory(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionEventCategory fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionEventCategory code: " + code));
    }
}
