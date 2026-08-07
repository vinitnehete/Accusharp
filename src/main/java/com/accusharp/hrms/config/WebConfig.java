package com.accusharp.hrms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Dev-only CORS: lets a browser-hosted client (the Accusharp web frontend, or
 * this app's Expo web preview) call the API from a different origin/port.
 * Native mobile clients (Expo Go, a built app) never hit browser CORS at all,
 * so this file is irrelevant to them - it only matters for browser testing.
 * Safe to delete before a real deployment; see README.md "Before going live".
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
