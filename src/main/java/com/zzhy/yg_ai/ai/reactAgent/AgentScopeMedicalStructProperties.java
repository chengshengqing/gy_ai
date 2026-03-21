package com.zzhy.yg_ai.ai.reactAgent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.agentscope.medical-struct")
public class AgentScopeMedicalStructProperties {

    private String apiKey;

    private String baseUrl;

    private String modelName;

    private Integer classifyBatchSize = 1;

    private Integer structuredBatchSize = 10;

    private Integer maxRetries = 3;

    private String classifyAgentName = "medical-struct-classifier";

    private String eventAgentName = "medical-event-extractor";

    private String classifySystemPrompt = "你是一个严谨的医疗病程结构化 ReActAgent，只能输出合法 JSON。";

    private String eventSystemPrompt = "你是一个严谨的医疗事件抽取 ReActAgent，只能输出合法 JSON。";
}
