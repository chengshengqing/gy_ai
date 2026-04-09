package com.zzhy.yg_ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "infection.format")
public class StructDataFormatProperties {

    private int batchSize = 5;
    private int maxAttempts = 5;
    private int retryDelaySeconds = 300;
    private int runningTimeoutSeconds = 1800;
}
