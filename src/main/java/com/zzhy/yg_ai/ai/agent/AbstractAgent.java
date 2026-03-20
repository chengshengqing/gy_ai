package com.zzhy.yg_ai.ai.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;


public abstract class AbstractAgent {

    @Autowired
    protected ChatModel chatModel;

    protected String callModel(String prompt) {
        return chatModel.call(prompt);
    }

    protected ChatResponse callModelByPrompt(Prompt prompt) {
        return chatModel.call(prompt);
    }
}
