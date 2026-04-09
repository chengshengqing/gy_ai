package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.common.InfectionRedisKeys;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.domain.enums.SummaryContextType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SummaryContextCacheServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void getOrBuildEventExtractorContextReturnsPreviousSevenDaysFromCacheOnly() throws Exception {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        InfectionMonitorProperties properties = new InfectionMonitorProperties();
        properties.setSummaryWindowDays(7);

        String reqno = "REQ-1";
        String cacheKey = InfectionRedisKeys.patientSummaryContext(SummaryContextType.EVENT_EXTRACTOR_CONTEXT, reqno);
        when(valueOperations.get(cacheKey)).thenReturn("""
                {
                  "reqno":"REQ-1",
                  "changes":[
                    "2026-03-01 窗口外",
                    "2026-03-02 第1天",
                    "2026-03-03 第2天",
                    "2026-03-04 第3天",
                    "2026-03-05 第4天",
                    "2026-03-06 第5天",
                    "2026-03-07 第6天",
                    "2026-03-08 第7天",
                    "2026-03-09 当天不应包含"
                  ]
                }
                """);

        SummaryContextCacheServiceImpl service = new SummaryContextCacheServiceImpl(
                stringRedisTemplate,
                objectMapper,
                properties
        );

        String result = service.getOrBuildEventExtractorContext(reqno, LocalDate.of(2026, 3, 9));

        JsonNode root = objectMapper.readTree(result);
        assertEquals("REQ-1", root.path("reqno").asText());
        assertFalse(root.has("anchorDate"));
        assertIterableEquals(List.of(
                "2026-03-02 第1天",
                "2026-03-03 第2天",
                "2026-03-04 第3天",
                "2026-03-05 第4天",
                "2026-03-06 第5天",
                "2026-03-07 第6天",
                "2026-03-08 第7天"
        ), textList(root.path("changes")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getOrBuildEventExtractorContextReturnsEmptyWhenCacheMissing() throws Exception {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        InfectionMonitorProperties properties = new InfectionMonitorProperties();
        properties.setSummaryWindowDays(7);

        String reqno = "REQ-1";
        String cacheKey = InfectionRedisKeys.patientSummaryContext(SummaryContextType.EVENT_EXTRACTOR_CONTEXT, reqno);
        when(valueOperations.get(cacheKey)).thenReturn(null);

        SummaryContextCacheServiceImpl service = new SummaryContextCacheServiceImpl(
                stringRedisTemplate,
                objectMapper,
                properties
        );

        String result = service.getOrBuildEventExtractorContext(reqno, LocalDate.of(2026, 3, 9));

        JsonNode root = objectMapper.readTree(result);
        assertEquals("REQ-1", root.path("reqno").asText());
        assertEquals(0, root.path("changes").size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getOrBuildEventExtractorContextKeepsLatestEightWithinWindow() throws Exception {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        InfectionMonitorProperties properties = new InfectionMonitorProperties();
        properties.setSummaryWindowDays(10);

        String reqno = "REQ-1";
        String cacheKey = InfectionRedisKeys.patientSummaryContext(SummaryContextType.EVENT_EXTRACTOR_CONTEXT, reqno);
        when(valueOperations.get(cacheKey)).thenReturn("""
                {
                  "reqno":"REQ-1",
                  "changes":[
                    "2026-03-01 1",
                    "2026-03-02 2",
                    "2026-03-03 3",
                    "2026-03-04 4",
                    "2026-03-05 5",
                    "2026-03-06 6",
                    "2026-03-07 7",
                    "2026-03-08 8",
                    "2026-03-09 9",
                    "2026-03-10 当天不应包含"
                  ]
                }
                """);

        SummaryContextCacheServiceImpl service = new SummaryContextCacheServiceImpl(
                stringRedisTemplate,
                objectMapper,
                properties
        );

        String result = service.getOrBuildEventExtractorContext(reqno, LocalDate.of(2026, 3, 10));

        JsonNode root = objectMapper.readTree(result);
        assertIterableEquals(List.of(
                "2026-03-02 2",
                "2026-03-03 3",
                "2026-03-04 4",
                "2026-03-05 5",
                "2026-03-06 6",
                "2026-03-07 7",
                "2026-03-08 8",
                "2026-03-09 9"
        ), textList(root.path("changes")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshEventExtractorContextDayAddsOrReplacesDailySummaryLine() throws Exception {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        InfectionMonitorProperties properties = new InfectionMonitorProperties();
        properties.setSummaryWindowDays(7);

        String reqno = "REQ-1";
        String cacheKey = InfectionRedisKeys.patientSummaryContext(SummaryContextType.EVENT_EXTRACTOR_CONTEXT, reqno);
        when(valueOperations.get(cacheKey)).thenReturn("""
                {
                  "reqno":"REQ-1",
                  "changes":[
                    "2026-03-02 旧摘要",
                    "2026-03-03 待替换摘要"
                  ]
                }
                """);

        SummaryContextCacheServiceImpl service = new SummaryContextCacheServiceImpl(
                stringRedisTemplate,
                objectMapper,
                properties
        );

        service.refreshEventExtractorContextDay(reqno, LocalDate.of(2026, 3, 3), """
                {
                  "time":"2026-03-03",
                  "record_type":"daily_fusion",
                  "day_summary":"新的当日摘要"
                }
                """);

        verify(valueOperations).set(cacheKey, "{\"reqno\":\"REQ-1\",\"changes\":[\"2026-03-02 旧摘要\",\"2026-03-03 新的当日摘要\"]}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshEventExtractorContextDayRemovesDailySummaryWhenEventJsonCleared() throws Exception {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        InfectionMonitorProperties properties = new InfectionMonitorProperties();
        properties.setSummaryWindowDays(7);

        String reqno = "REQ-1";
        String cacheKey = InfectionRedisKeys.patientSummaryContext(SummaryContextType.EVENT_EXTRACTOR_CONTEXT, reqno);
        when(valueOperations.get(cacheKey)).thenReturn("""
                {
                  "reqno":"REQ-1",
                  "changes":[
                    "2026-03-02 保留",
                    "2026-03-03 删除我"
                  ]
                }
                """);

        SummaryContextCacheServiceImpl service = new SummaryContextCacheServiceImpl(
                stringRedisTemplate,
                objectMapper,
                properties
        );

        service.refreshEventExtractorContextDay(reqno, LocalDate.of(2026, 3, 3), null);

        verify(valueOperations).set(cacheKey, "{\"reqno\":\"REQ-1\",\"changes\":[\"2026-03-02 保留\"]}");
    }

    private List<String> textList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(item.asText());
        }
        return result;
    }
}
