package com.zzhy.yg_ai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.zzhy.yg_ai.mapper")
public class MybatisPlusConfig {
    /**
     * 添加分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 使用 SQL_SERVER 方言 (支持 OFFSET/FETCH 语法)
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.SQL_SERVER2005);
        interceptor.addInnerInterceptor(paginationInterceptor);

        return interceptor;
    }
}
