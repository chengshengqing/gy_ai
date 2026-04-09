package com.zzhy.yg_ai.ai.gateway;

public interface WarningModelGateway {

    String callEventExtractor(String systemPrompt, String inputJson);

    String callStructuredFactRefinement(String systemPrompt, String inputJson);

    String callCaseJudge(String systemPrompt, String inputJson);
}
