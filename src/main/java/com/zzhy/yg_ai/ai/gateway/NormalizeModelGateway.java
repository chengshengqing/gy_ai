package com.zzhy.yg_ai.ai.gateway;

public interface NormalizeModelGateway {

    String callSystemPrompt(String systemPrompt, String inputJson);

    String callSystemAndUserPrompt(String systemPrompt, String userPrompt, String inputJson);
}
