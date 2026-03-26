package com.zzhy.yg_ai.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WarningAgent extends AbstractAgent {

    private final ObjectMapper objectMapper;

    public WarningAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


}
