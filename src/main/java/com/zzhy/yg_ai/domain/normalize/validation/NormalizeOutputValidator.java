package com.zzhy.yg_ai.domain.normalize.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.gateway.NormalizeModelGateway;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NormalizeOutputValidator {

    private final ObjectMapper objectMapper;
    private final NormalizeModelGateway normalizeModelGateway;
    private final NormalizePromptOutputValidator promptOutputValidator;
    private final NormalizeRetryInstructionBuilder retryInstructionBuilder;

    public NormalizeOutputValidator(ObjectMapper objectMapper,
                                    NormalizeModelGateway normalizeModelGateway,
                                    NormalizePromptOutputValidator promptOutputValidator,
                                    NormalizeRetryInstructionBuilder retryInstructionBuilder) {
        this.objectMapper = objectMapper;
        this.normalizeModelGateway = normalizeModelGateway;
        this.promptOutputValidator = promptOutputValidator;
        this.retryInstructionBuilder = retryInstructionBuilder;
    }

    public NormalizeValidatedResult validateWithRetry(NormalizePromptDefinition promptDefinition, String inputJson) {
        List<NormalizeAttemptReport> attempts = new ArrayList<>();
        List<NormalizeValidationIssue> issues = Collections.emptyList();
        for (int attempt = 1; attempt <= 3; attempt++) {
            String rawOutput = callPrompt(promptDefinition, inputJson, issues);
            NormalizeValidationResult validationResult = promptOutputValidator.validatePromptOutput(promptDefinition, rawOutput);
            attempts.add(new NormalizeAttemptReport(attempt, validationResult.valid(), validationResult.issues()));
            if (validationResult.valid()) {
                return new NormalizeValidatedResult(true, validationResult.parsedNode(), attempts, "");
            }
            issues = validationResult.issues();
        }
        String failureReason = issues.isEmpty() ? "LLM output validation failed" : issues.get(0).reason();
        log.warn("Prompt validation failed after retries, promptType={}, reason={}", promptDefinition.type(), failureReason);
        return new NormalizeValidatedResult(false, objectMapper.createObjectNode(), attempts, failureReason);
    }

    public Map<String, Object> failureReport(String reason) {
        return Map.of(
                "success", false,
                "attempt_count", 0,
                "failure_reason", reason,
                "attempts", List.of()
        );
    }

    private String callPrompt(NormalizePromptDefinition promptDefinition, String inputJson, List<NormalizeValidationIssue> issues) {
        if (!promptDefinition.hasUserPrompt()) {
            String userInput = issues == null || issues.isEmpty()
                    ? inputJson
                    : retryInstructionBuilder.buildRetryUserInput(inputJson, issues);
            return normalizeModelGateway.callSystemPrompt(promptDefinition.systemPrompt(), userInput);
        }
        String userPrompt = promptDefinition.userPrompt();
        if (issues != null && !issues.isEmpty()) {
            userPrompt = userPrompt + "\n\n" + retryInstructionBuilder.buildRetryInstruction(issues);
        }
        return normalizeModelGateway.callSystemAndUserPrompt(promptDefinition.systemPrompt(), userPrompt, inputJson);
    }
}
