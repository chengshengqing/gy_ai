package com.zzhy.yg_ai.ai.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.zzhy.yg_ai.ai.tools.DataHandlerTool;
import com.zzhy.yg_ai.ai.tools.LoadDataTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfectionAgentConfig {

    @Bean(name = "formatReactAgent")
    public ReactAgent formatReactAgent(ChatModel chatModel,
                                       LoadDataTool loadDataTool,
                                       DataHandlerTool dataHandlerTool,
                                       RedisSaver reactAgentRedisSaver) {
        return ReactAgent.builder()
                .name("format-agent")
                .description("Format raw data into standard JSON context.")
                .model(chatModel)
                .methodTools(loadDataTool, dataHandlerTool)
                .instruction("Format raw patient data into a normalized JSON context.")
                .saver(reactAgentRedisSaver)
                .build();
    }

    @Bean(name = "summaryReactAgent")
    public ReactAgent summaryReactAgent(ChatModel chatModel,
                                        RedisSaver reactAgentRedisSaver) {
        return ReactAgent.builder()
                .name("summary-agent")
                .description("Compress historical data with rolling summary.")
                .model(chatModel)
                .instruction("Summarize old_summary + new_data into a rolling summary.")
                .saver(reactAgentRedisSaver)
                .build();
    }

    @Bean(name = "warningReactAgent")
    public ReactAgent warningReactAgent(ChatModel chatModel,
                                        RedisSaver reactAgentRedisSaver) {
        return ReactAgent.builder()
                .name("warning-agent")
                .description("Identify infection risks from summary.")
                .model(chatModel)
                .instruction("Identify infection risks and propose alerts.")
                .saver(reactAgentRedisSaver)
                .build();
    }

    @Bean(name = "auditReactAgent")
    public ReactAgent auditReactAgent(ChatModel chatModel,
                                      RedisSaver reactAgentRedisSaver) {
        return ReactAgent.builder()
                .name("audit-agent")
                .description("Reduce false positives by auditing alerts.")
                .model(chatModel)
                .instruction("Audit alerts with evidence to reduce false positives.")
                .saver(reactAgentRedisSaver)
                .build();
    }
}
