package com.example.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /** 统一服务端时钟：进入、保存、提交和评测时间全部由服务端生成（公平性要求）。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
