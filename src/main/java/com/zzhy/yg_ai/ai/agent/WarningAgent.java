package com.zzhy.yg_ai.ai.agent;

import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * 预留院感预警 Agent。
 */
@Component
public class WarningAgent extends AbstractAgent {

    public String callEventExtractor(String systemPrompt, String inputJson) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(inputJson)
        ));
        ChatResponse response = super.callModelByPrompt(prompt);
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }

    public String callStructuredFactRefinement(String systemPrompt, String inputJson) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(inputJson)
        ));
        ChatResponse response = super.callModelByPrompt(prompt);
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }
}
