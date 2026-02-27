package com.ravik.mcp.model;

import java.util.List;
import java.util.Map;

public record RestApiDefinition(
        String toolName,
        String description,
        String method,
        String url,
        List<ParameterDef> parameters,
        Map<String, String> defaultHeaders
) {
    public RestApiDefinition {
        if (defaultHeaders == null) defaultHeaders = Map.of();
    }
}