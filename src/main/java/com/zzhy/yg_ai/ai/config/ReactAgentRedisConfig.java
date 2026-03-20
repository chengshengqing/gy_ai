package com.zzhy.yg_ai.ai.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.memory.redis.LettuceRedisChatMemoryRepository;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(ReactAgentRedisProperties.class)
public class ReactAgentRedisConfig {

    @Bean(name = "reactAgentChatMemoryRepository")
    public ChatMemoryRepository reactAgentChatMemoryRepository(ReactAgentRedisProperties properties) {
        LettuceRedisChatMemoryRepository.RedisBuilder builder = LettuceRedisChatMemoryRepository.builder()
                .host(properties.getHost())
                .port(properties.getPort())
                .database(properties.getDatabase())
                .timeout(properties.getTimeout())
                .keyPrefix(properties.getKeyPrefix())
                .useSsl(properties.isUseSsl());
        if (StringUtils.hasText(properties.getUsername())) {
            builder.username(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            builder.password(properties.getPassword());
        }
        return builder.build();
    }

    @Bean(name = "reactAgentRedissonClient", destroyMethod = "shutdown")
    public RedissonClient reactAgentRedissonClient(ReactAgentRedisProperties properties) {
        String schema = properties.isUseSsl() ? "rediss://" : "redis://";
        Config config = new Config();
        config.useSingleServer()
                .setAddress(schema + properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase())
                .setConnectTimeout(properties.getTimeout())
                .setTimeout(properties.getTimeout());
        if (StringUtils.hasText(properties.getUsername())) {
            config.useSingleServer().setUsername(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            config.useSingleServer().setPassword(properties.getPassword());
        }
        return Redisson.create(config);
    }

    @Bean(name = "reactAgentRedisSaver")
    public RedisSaver reactAgentRedisSaver(@Qualifier("reactAgentRedissonClient") RedissonClient reactAgentRedissonClient) {
        return RedisSaver.builder()
                .redisson(reactAgentRedissonClient)
                .build();
    }
}
