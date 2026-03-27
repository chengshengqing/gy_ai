package com.zzhy.yg_ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "infection.format")
public class StructDataFormatProperties {

    private int fixedDelay = 30000;
    private int batchSize = 50;
    private int workerThreads = 8;
    private int maxAttempts = 5;
    private int retryDelaySeconds = 300;
}
