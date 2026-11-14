package com.yuyay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    /** Single time source for components that need to be tested with a fixed clock. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
