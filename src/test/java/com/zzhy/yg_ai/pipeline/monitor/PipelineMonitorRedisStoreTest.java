package com.zzhy.yg_ai.pipeline.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.config.PipelineMonitorProperties;
import com.zzhy.yg_ai.domain.dto.monitor.PipelineMonitorDashboardView;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class PipelineMonitorRedisStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void refreshModelRuntimeIgnoresRedisFailures() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForHash()).thenReturn((HashOperations) hashOperations);
        doThrow(new RuntimeException("redis down")).when(hashOperations).putAll(anyString(), anyMap());
        PipelineMonitorRedisStore store = newStore(stringRedisTemplate);

        assertThatCode(() -> store.refreshModelRuntime(12, 1)).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadRuntimeViewFallsBackToEmptySnapshotWhenRedisReadFails() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForHash()).thenReturn((HashOperations) hashOperations);
        when(hashOperations.entries(anyString())).thenThrow(new RuntimeException("redis down"));
        PipelineMonitorRedisStore store = newStore(stringRedisTemplate);

        PipelineMonitorDashboardView.RuntimeView runtimeView = store.loadRuntimeView();

        assertThat(runtimeView.executor().totalThreads()).isZero();
        assertThat(runtimeView.executor().updatedAt()).isNull();
        assertThat(runtimeView.model().totalPermits()).isZero();
        assertThat(runtimeView.model().updatedAt()).isNull();
    }

    private PipelineMonitorRedisStore newStore(StringRedisTemplate stringRedisTemplate) {
        PipelineMonitorProperties properties = new PipelineMonitorProperties();
        properties.setEnabled(true);
        return new PipelineMonitorRedisStore(stringRedisTemplate, new ObjectMapper(), properties);
    }
}
