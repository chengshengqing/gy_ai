package com.zzhy.yg_ai.domain.normalize.validation;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NormalizeRetryInstructionBuilder {

    public String buildRetryUserInput(String inputJson, List<NormalizeValidationIssue> issues) {
        return buildRetryInstruction(issues) + "\n【输入数据】\n" + inputJson;
    }

    public String buildRetryInstruction(List<NormalizeValidationIssue> issues) {
        StringBuilder builder = new StringBuilder();
        builder.append("上一次输出未通过校验，请仅修正以下问题，并重新输出完整JSON：\n");
        for (int i = 0; i < issues.size(); i++) {
            NormalizeValidationIssue issue = issues.get(i);
            builder.append(i + 1).append(". 路径 ").append(issue.jsonPath());
            if (StringUtils.hasText(issue.invalidValue())) {
                builder.append(" 的值为 ").append(issue.invalidValue());
            }
            if (!issue.allowedValues().isEmpty()) {
                builder.append("，允许值仅为 ").append(String.join("|", issue.allowedValues()));
            }
            builder.append("。原因：").append(issue.reason()).append('\n');
        }
        builder.append("不要输出解释，只输出修正后的完整JSON。");
        return builder.toString();
    }
}
