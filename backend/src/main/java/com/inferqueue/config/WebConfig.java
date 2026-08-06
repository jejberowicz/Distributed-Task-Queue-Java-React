package com.inferqueue.config;

import com.inferqueue.security.CurrentApiKeyResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentApiKeyResolver currentApiKeyResolver;

    public WebConfig(CurrentApiKeyResolver currentApiKeyResolver) {
        this.currentApiKeyResolver = currentApiKeyResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentApiKeyResolver);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // El dashboard corre en otro origen en desarrollo (Vite en :5173).
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
