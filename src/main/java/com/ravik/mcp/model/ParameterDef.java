package com.ravik.mcp.model;

public record ParameterDef(
        String name,
        String type,           // "string" | "integer" | "number" | "boolean" | "object"
        String description,
        boolean required,
        String in              // "path", "query", "header", "cookie", "body"
) {}
