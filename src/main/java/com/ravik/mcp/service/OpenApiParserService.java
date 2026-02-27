package com.ravik.mcp.service;

import com.ravik.mcp.config.OpenApiMcpProperties;
import com.ravik.mcp.model.ParameterDef;
import com.ravik.mcp.model.RestApiDefinition;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;

import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OpenApiParserService {

    private final OpenApiMcpProperties properties;
    private final List<RestApiDefinition> definitions = new CopyOnWriteArrayList<>();

    public OpenApiParserService(OpenApiMcpProperties properties) {
        this.properties = properties;
    }

    /**
     * Parse ALL OpenAPI specs during bean initialization (before ToolCallbackProvider is built).
     * This guarantees tools are available when MCP server starts.
     */
    @PostConstruct
    void parseAllSpecs() {
        System.out.println("🔄 Parsing OpenAPI specs for MCP tools...");
        properties.sources().forEach(this::parseSource);
        System.out.println("✅ Parsed " + definitions.size() + " MCP tools from " + properties.sources().size() + " OpenAPI sources.");
    }

    private void parseSource(OpenApiMcpProperties.Source source) {
        try {
            OpenAPIParser parser = new OpenAPIParser();
            SwaggerParseResult result = parser.readLocation(source.specUrl(), null, null);

            if (result.getOpenAPI() == null) {
                System.err.println("❌ Failed to parse: " + source.specUrl() + " → " + result.getMessages());
                return;
            }

            OpenAPI openAPI = result.getOpenAPI();
            String baseUrl = (openAPI.getServers() != null && !openAPI.getServers().isEmpty())
                    ? openAPI.getServers().get(0).getUrl() : "";

            // Handle relative baseUrl
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

            String finalBaseUrl = baseUrl;
            openAPI.getPaths().forEach((path, pathItem) -> {
                pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                    String toolName = operation.getOperationId() != null
                            ? operation.getOperationId()
                            : (path.replaceAll("[^a-zA-Z0-9]", "_") + "_" + httpMethod.name().toLowerCase());

                    List<ParameterDef> params = buildParameters(operation);

                    String fullUrl = finalBaseUrl + path;
                    definitions.add(new RestApiDefinition(
                            toolName,
                            operation.getSummary() != null ? operation.getSummary() : (operation.getDescription() != null ? operation.getDescription() : toolName),
                            httpMethod.name(),
                            fullUrl,
                            params,
                            source.defaultHeaders()
                    ));
                });
            });

        } catch (Exception e) {
            System.err.println("⚠️ Error parsing " + source.specUrl() + ": " + e.getMessage());
        }
    }

    private List<ParameterDef> buildParameters(Operation operation) {
        List<ParameterDef> params = new ArrayList<>();

        // Path, query, header, etc.
        if (operation.getParameters() != null) {
            operation.getParameters().forEach(p -> {
                String type = (p.getSchema() != null && p.getSchema().getType() != null)
                        ? p.getSchema().getType() : "string";
                params.add(new ParameterDef(
                        p.getName(),
                        type,
                        p.getDescription(),
                        Boolean.TRUE.equals(p.getRequired()),
                        p.getIn()
                ));
            });
        }

        // Request body
        if (operation.getRequestBody() != null) {
            params.add(new ParameterDef(
                    "body",
                    "object",
                    operation.getRequestBody().getDescription() != null
                            ? operation.getRequestBody().getDescription() : "Request payload",
                    Boolean.TRUE.equals(operation.getRequestBody().getRequired()),
                    "body"
            ));
        }

        return params;
    }

    public List<RestApiDefinition> getAllDefinitions() {
        return Collections.unmodifiableList(definitions);
    }
}