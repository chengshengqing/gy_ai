package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 病例级院感状态。
 * 对应 infection_case_snapshot.case_state 与法官输出的 decisionStatus。
 */
public enum InfectionCaseState {
    NO_RISK("no_risk", "无风险"),
    CANDIDATE("candidate", "候选"),
    WARNING("warning", "预警"),
    RESOLVED("resolved", "已解除");

    private final String code;
    private final String description;

    InfectionCaseState(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionCaseState fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionCaseState code: " + code));
    }

    public static InfectionCaseState fromCodeOrDefault(String code, InfectionCaseState defaultValue) {
        try {
            return fromCode(code);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public static Set<String> allCodes() {
        return Arrays.stream(values()).map(InfectionCaseState::code).collect(Collectors.toUnmodifiableSet());
    }

    public boolean isActiveRisk() {
        return this != NO_RISK && this != RESOLVED;
    }
}
