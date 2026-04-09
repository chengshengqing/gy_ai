package com.zzhy.yg_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "infection.monitor.dashboard")
public class PipelineMonitorProperties {

    private boolean enabled = true;
    private int bucketTtlHours = 30;
    private long llmSlowThresholdMs = 5000L;
}
