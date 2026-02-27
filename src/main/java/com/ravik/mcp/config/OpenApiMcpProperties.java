package com.ravik.mcp.config;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "mcp.openapi")
public record OpenApiMcpProperties(List<Source> sources) {

    public record Source(
            String name,
            String specUrl,                    // http:// or file:
            Map<String, String> defaultHeaders // optional per source
    ) {
        @ConstructorBinding
        public Source(String name, String specUrl, Map<String, String> defaultHeaders) {
            this.name = name;
            this.specUrl = specUrl;
            this.defaultHeaders = defaultHeaders != null ? defaultHeaders : Map.of();
        }
    }
}
