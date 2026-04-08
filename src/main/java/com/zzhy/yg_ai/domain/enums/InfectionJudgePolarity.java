package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 病例级感染倾向极性（法官裁决层）。
 * 描述整体证据对"感染成立"的支持/反对方向。
 * <p>
 * 与 {@link InfectionPolarity} 的区别：
 * InfectionPolarity 描述的是单条事件的极性（positive/negative/neutral/uncertain），
 * InfectionJudgePolarity 描述的是病例级综合裁决方向（support/against/uncertain）。
 */
public enum InfectionJudgePolarity {
    SUPPORT("support", "支持感染"),
    AGAINST("against", "反对感染"),
    UNCERTAIN("uncertain", "未定");

    private final String code;
    private final String description;

    InfectionJudgePolarity(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionJudgePolarity fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionJudgePolarity code: " + code));
    }

    public static InfectionJudgePolarity fromCodeOrDefault(String code, InfectionJudgePolarity defaultValue) {
        try {
            return fromCode(code);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public static Set<String> allCodes() {
        return Arrays.stream(values()).map(InfectionJudgePolarity::code).collect(Collectors.toUnmodifiableSet());
    }
}
