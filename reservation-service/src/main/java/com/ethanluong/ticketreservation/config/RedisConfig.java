package com.ethanluong.ticketreservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> releaseHoldScript(){
        return RedisScript.of(new ClassPathResource("scripts/release-hold.lua"), Long.class);
    }
}
