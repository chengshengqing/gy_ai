package com.zzhy.yg_ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class YgAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(YgAiApplication.class, args);
    }

}
