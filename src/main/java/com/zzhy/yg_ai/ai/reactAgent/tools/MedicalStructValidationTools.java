package com.zzhy.yg_ai.ai.reactAgent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.annotation.Tool;
import io.agentscope.core.tool.annotation.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MedicalStructValidationTools {

    private static final List<String> REQUIRED_STRUCTURED_FIELDS = List.of(
            "courseTime",
            "symptoms",
            "signsAndExaminations",
            "doctorAssessment",
            "surgeryRecords",
            "treatmentPlan",
            "consultationOpinions",
            "unclassified"
    );

    private final ObjectMapper objectMapper;

    @Tool(name = "validate_structured_json", description = "校验病程一级结构化结果是否为合法 JSON 且包含全部必需字段")
    public String validateStructuredJson(
            @ToolParam(name = "json", description = "待校验的结构化 JSON 字符串") String json) {
        return validateStructured(json);
    }

    @Tool(name = "validate_event_json", description = "校验事件抽取结果是否为合法 JSON 或 JSON 数组")
    public String validateEventJson(
            @ToolParam(name = "json", description = "待校验的事件 JSON 字符串") String json) {
        try {
            objectMapper.readTree(json);
            return "VALID";
        } catch (Exception ex) {
            return "INVALID: " + ex.getMessage();
        }
    }

    public String validateStructured(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> missingFields = new ArrayList<>();
            for (String field : REQUIRED_STRUCTURED_FIELDS) {
                if (!node.has(field)) {
                    missingFields.add(field);
                }
            }
            if (missingFields.isEmpty()) {
                return "VALID";
            }
            return "INVALID: missing fields " + String.join(",", missingFields);
        } catch (Exception ex) {
            return "INVALID: " + ex.getMessage();
        }
    }
}
