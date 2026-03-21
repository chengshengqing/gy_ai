package com.zzhy.yg_ai.ai.reactAgent;

import com.zzhy.yg_ai.ai.reactAgent.tools.MedicalStructValidationTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.ExecutionConfig;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentScopeMedicalAgentFactory {

    private final AgentScopeMedicalStructProperties properties;
    private final MedicalStructValidationTools validationTools;

    public ReActAgent createClassificationAgent() {
        return ReActAgent.builder()
                .name(properties.getClassifyAgentName())
                .sysPrompt(properties.getClassifySystemPrompt())
                .toolkit(createToolkit())
                .model(createModel())
                .modelExecutionConfig(createModelExecutionConfig())
                .toolExecutionConfig(createToolExecutionConfig())
                .checkRunning(true)
                .maxIters(8)
                .build();
    }

    public ReActAgent createEventAgent() {
        return ReActAgent.builder()
                .name(properties.getEventAgentName())
                .sysPrompt(properties.getEventSystemPrompt())
                .toolkit(createToolkit())
                .model(createModel())
                .modelExecutionConfig(createModelExecutionConfig())
                .toolExecutionConfig(createToolExecutionConfig())
                .checkRunning(true)
                .maxIters(8)
                .build();
    }

    private Toolkit createToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(validationTools);
        return toolkit;
    }

    private ExecutionConfig createModelExecutionConfig() {
        return ExecutionConfig.builder()
                .maxAttempts(properties.getMaxRetries())
                .build();
    }

    private ExecutionConfig createToolExecutionConfig() {
        return ExecutionConfig.builder()
                .maxAttempts(1)
                .build();
    }

    private OpenAIChatModel createModel() {
        return OpenAIChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(properties.getModelName())
                .build();
    }
}
