package com.zzhy.yg_ai.ai.reactAgent;

import com.zzhy.yg_ai.ai.prompt.PromptTemplateManager;
import com.zzhy.yg_ai.ai.reactAgent.tools.MedicalStructValidationTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeMedicalStructRunner {

    private final AgentScopeMedicalAgentFactory agentFactory;
    private final AgentScopeMedicalStructProperties properties;
    private final PromptTemplateManager promptTemplateManager;
    private final MedicalStructValidationTools validationTools;

    public String classify(String illnessContent) {
        String prompt = promptTemplateManager.buildStructPrompt(illnessContent);
        return invokeWithRetry(agentFactory.createClassificationAgent(), prompt, true);
    }

    public String extractSymptomEvents(String text) {
        return invokeEvent(promptTemplateManager.buildSymptomPrompt(text));
    }

    public String extractExamEvents(String text) {
        return invokeEvent(promptTemplateManager.buildExamPrompt(text));
    }

    public String extractImagingEvents(String text) {
        return invokeEvent(promptTemplateManager.buildImagePrompt(text));
    }

    public String extractProcedureEvents(String text) {
        return invokeEvent(promptTemplateManager.buildProcedurePrompt(text));
    }

    public String extractSurgeryEvents(String text) {
        return invokeEvent(promptTemplateManager.buildSurgeryPrompt(text));
    }

    public String extractMedicationEvents(String text) {
        return invokeEvent(promptTemplateManager.buildMedicationPrompt(text));
    }

    public String extractTransferEvents(String text) {
        return invokeEvent(promptTemplateManager.buildTransferPrompt(text));
    }

    private String invokeEvent(String prompt) {
        return invokeWithRetry(agentFactory.createEventAgent(), prompt, false);
    }

    private String invokeWithRetry(ReActAgent agent, String prompt, boolean structuredValidation) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                Msg response = agent.call(Msg.builder().textContent(prompt).build()).block();
                String textContent = response == null ? null : response.getTextContent();
                if (textContent == null || textContent.isBlank()) {
                    throw new IllegalStateException("AgentScope 返回内容为空");
                }
                String validationResult = structuredValidation
                        ? validationTools.validateStructured(textContent)
                        : validationTools.validateEventJson(textContent);
                if (!"VALID".equals(validationResult)) {
                    throw new IllegalStateException(validationResult);
                }
                return textContent;
            } catch (RuntimeException ex) {
                lastException = ex;
                log.warn("AgentScope 调用失败，第 {} 次重试。原因: {}", attempt, ex.getMessage());
            }
        }
        throw new IllegalStateException("AgentScope 调用失败，超过最大重试次数", lastException);
    }
}
