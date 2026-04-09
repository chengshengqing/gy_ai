package com.zzhy.yg_ai.ai.gateway;

import com.zzhy.yg_ai.ai.agent.AgentUtils;
import com.zzhy.yg_ai.pipeline.scheduler.limiter.ModelCallGuard;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringAiFormatModelGateway implements FormatModelGateway {

    private final ChatModel chatModel;
    private final ModelCallGuard modelCallGuard;

    @Override
    public String callPrompt(String systemPrompt, String inputJson) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(inputJson)
        ));
        ChatResponse response = modelCallGuard.call(PipelineStage.NORMALIZE, "FORMAT_SECTION", () -> chatModel.call(prompt));
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }
}
