package com.ravik.mcp.config;


import com.ravik.mcp.service.OpenApiParserService;

import com.ravik.mcp.tools.RestApiToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider openApiToolProvider(OpenApiParserService parserService) {
        List<RestApiToolCallback> callbacks = parserService.getAllDefinitions().stream()
                .map(RestApiToolCallback::new)
                .toList();

        return ToolCallbackProvider.from(callbacks);
    }
}
