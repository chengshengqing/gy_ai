package com.zzhy.yg_ai.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "infection.monitor")
public class InfectionMonitorProperties {

    private int scanLimit = 200;
    private int batchSize = 100;
    private int workerThreads = 8;
    private int maxAttempts = 5;
    private int retryDelaySeconds = 300;
    private int runningTimeoutSeconds = 1800;
    private int recentAdmissionDays = 30;
    private boolean debugMode = false;
    private List<String> debugReqnos = new ArrayList<>();
}
