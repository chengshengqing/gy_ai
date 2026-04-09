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
public class SpringAiWarningModelGateway implements WarningModelGateway {

    private final ChatModel chatModel;
    private final ModelCallGuard modelCallGuard;

    @Override
    public String callEventExtractor(String systemPrompt, String inputJson) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(inputJson)
        ));
        ChatResponse response = modelCallGuard.call(PipelineStage.EVENT_EXTRACT, "EVENT_EXTRACTOR", () -> chatModel.call(prompt));
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }

    @Override
    public String callStructuredFactRefinement(String systemPrompt, String inputJson) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(inputJson)
        ));
        ChatResponse response = modelCallGuard.call(PipelineStage.EVENT_EXTRACT, "STRUCTURED_FACT_REFINEMENT", () -> chatModel.call(prompt));
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }

    @Override
    public String callCaseJudge(String systemPrompt, String inputJson) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(inputJson)
        ));
        ChatResponse response = modelCallGuard.call(PipelineStage.CASE_RECOMPUTE, "CASE_JUDGE", () -> chatModel.call(prompt));
        return AgentUtils.normalizeToJson(response.getResult().getOutput().getText());
    }
}
