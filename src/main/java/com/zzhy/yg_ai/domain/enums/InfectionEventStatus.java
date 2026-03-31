package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionEventStatus {
    ACTIVE("active", "有效"),
    REVOKED("revoked", "撤销"),
    SUPERSEDED("superseded", "被新版本替代"),
    INVALID("invalid", "无效");

    private final String code;
    private final String description;

    InfectionEventStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionEventStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionEventStatus code: " + code));
    }
}
