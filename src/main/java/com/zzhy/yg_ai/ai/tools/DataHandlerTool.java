package com.zzhy.yg_ai.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class DataHandlerTool {

    private final ObjectMapper objectMapper;

    public DataHandlerTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(name = "clean_patient_data",
            description = "Clean and normalize raw patient data.")
    public String cleanPatientData(@ToolParam(description = "Raw data JSON") String rawDataJson) {
        if (!StringUtils.hasText(rawDataJson)) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(rawDataJson);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("clean_patient_data: 输入非标准JSON，返回原始字符串，err={}", e.getMessage());
            return rawDataJson;
        }
    }
}
