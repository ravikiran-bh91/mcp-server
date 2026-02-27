package com.ravik.mcp.tools;

import com.ravik.mcp.model.ParameterDef;
import com.ravik.mcp.model.RestApiDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriBuilder;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
@Slf4j
public class RestApiToolCallback implements ToolCallback {

    private final RestApiDefinition def;
    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    public RestApiToolCallback(RestApiDefinition def) {
        this.def = def;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        def.parameters().forEach(p -> {
            properties.put(p.name(), Map.of(
                    "type", p.type(),
                    "description", p.description() != null ? p.description() : ""
            ));
            if (p.required()) required.add(p.name());
        });

        String schema;
        try {
            schema = """
                    {
                      "type": "object",
                      "properties": %s,
                      "required": %s,
                      "additionalProperties": false
                    }
                    """.formatted(
                    mapper.writeValueAsString(properties),
                    mapper.writeValueAsString(required)
            );
        } catch (Exception e) {
            schema = "{\"type\":\"object\"}";
        }

        return ToolDefinition.builder()
                .name(def.toolName())
                .description(def.description())
                .inputSchema(schema)
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(false).build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        log.info("╔════════════════════ MCP TOOL CALL START ════════════════════╗");
        log.info("║ Tool name       : {}", def.toolName());
        log.info("║ Tool method     : {}", def.method());
        log.info("║ Tool base URL   : {}", def.url());
        log.info("║ Input JSON size : {} chars", toolInput != null ? toolInput.length() : 0);
        log.info("║ Input JSON      : {}", toolInput != null ? toolInput : "<null>");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = mapper.readValue(toolInput, Map.class);
            log.info("║ Parsed arguments count : {}", args.size());
            log.info("║ Arguments keys         : {}", args.keySet());

            // ────────────────────────────────────────────────────────────────
            //  Authentication / Bearer token handling
            // ────────────────────────────────────────────────────────────────
            String bearerFromRequest = extractBearerTokenFromCurrentRequest();

            log.info("║ Bearer from RequestContextHolder : {}",
                    bearerFromRequest != null
                            ? bearerFromRequest.substring(0, Math.min(20, bearerFromRequest.length())) + "..."
                            : "MISSING / null");

            // Try to get token from ToolContext (correct API)
            String tokenFromContext = null;
            if (toolContext != null) {
                Map<String, Object> ctxMap = toolContext.getContext();
                if (ctxMap != null && !ctxMap.isEmpty()) {
                    log.info("║ ToolContext map keys present : {}", ctxMap.keySet());

                    // Try common keys where clients might put auth
                    tokenFromContext = (String) ctxMap.get("Authorization");
                    if (tokenFromContext == null) {
                        tokenFromContext = (String) ctxMap.get("accessToken");
                    }
                    if (tokenFromContext == null) {
                        tokenFromContext = (String) ctxMap.get("token");
                    }
                    if (tokenFromContext == null) {
                        tokenFromContext = (String) ctxMap.get("bearer");
                    }

                    log.info("║ Bearer from ToolContext : {}",
                            tokenFromContext != null
                                    ? tokenFromContext.substring(0, Math.min(20, tokenFromContext.length())) + "..."
                                    : "not found in context map");
                } else {
                    log.info("║ ToolContext present but context map is null or empty");
                }
            } else {
                log.info("║ ToolContext parameter is null");
            }

            // Final decision: prefer context → servlet request
            String finalBearer = tokenFromContext != null ? tokenFromContext : bearerFromRequest;

            log.info("║ FINAL Bearer token decided : {}",
                    finalBearer != null
                            ? finalBearer.substring(0, Math.min(20, finalBearer.length())) + "..."
                            : "NO TOKEN AVAILABLE");

            // ────────────────────────────────────────────────────────────────
            //  Path parameters resolution
            // ────────────────────────────────────────────────────────────────
            String resolvedUrl = def.url();
            log.info("║ Original URL template : {}", resolvedUrl);

            for (ParameterDef p : def.parameters()) {
                if ("path".equalsIgnoreCase(p.in())) {
                    Object val = args.remove(p.name());
                    if (val != null) {
                        String oldUrl = resolvedUrl;
                        resolvedUrl = resolvedUrl.replace("{" + p.name() + "}", val.toString());
                        log.info("║ Path param {} = {} → URL: {} → {}",
                                p.name(), val, oldUrl, resolvedUrl);
                    } else if (p.required()) {
                        log.warn("║ Required path param {} is missing!", p.name());
                    }
                }
            }

            // ────────────────────────────────────────────────────────────────
            //  Build request (UriBuilder for query params)
            // ────────────────────────────────────────────────────────────────
            final String finalResolvedUrl = resolvedUrl;

            RestClient.RequestHeadersSpec<?> requestSpec = restClient
                    .method(HttpMethod.valueOf(def.method()))
                    .uri(uriBuilder -> {
                        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(finalResolvedUrl);

                        int queryCount = 0;
                        for (ParameterDef p : def.parameters()) {
                            Object val = args.get(p.name());
                            if (val != null && "query".equalsIgnoreCase(p.in())) {
                                builder.queryParam(p.name(), val.toString());
                                queryCount++;
                                log.info("║ Query param : {} = {}", p.name(), val);
                            }
                        }
                        log.info("║ Total query params added : {}", queryCount);
                        return builder.build(true).toUri();
                    });

            log.info("║ Final resolved URI : {}", finalResolvedUrl);

            // ────────────────────────────────────────────────────────────────
            //  Apply auth header
            // ────────────────────────────────────────────────────────────────
            if (finalBearer != null && !finalBearer.isBlank()) {
                String authValue = finalBearer.startsWith("Bearer ")
                        ? finalBearer
                        : "Bearer " + finalBearer;
                requestSpec.header("Authorization", authValue);
                log.info("║ → Forwarding Authorization: {}", authValue.substring(0, Math.min(25, authValue.length())) + "...");
            } else {
                log.warn("║ → NO Authorization header sent downstream");
            }

            // ────────────────────────────────────────────────────────────────
            //  Default + custom header/cookie from args
            // ────────────────────────────────────────────────────────────────
            def.defaultHeaders().forEach((k, v) -> {
                requestSpec.header(k, v);
                log.info("║ Default header : {} = {}", k, v);
            });

            for (ParameterDef p : def.parameters()) {
                Object val = args.get(p.name());
                if (val == null) continue;
                String in = p.in() != null ? p.in().toLowerCase() : "";
                if ("header".equals(in)) {
                    requestSpec.header(p.name(), val.toString());
                    log.info("║ Header from args : {} = {}", p.name(), val);
                } else if ("cookie".equals(in)) {
                    requestSpec.cookie(p.name(), val.toString());
                    log.info("║ Cookie from args : {} = {}", p.name(), val);
                }
            }

            // ────────────────────────────────────────────────────────────────
            //  Body
            // ────────────────────────────────────────────────────────────────
            Object body = args.get("body");
            if (body != null && isBodySupportingMethod(def.method())) {
                ((RestClient.RequestBodySpec) requestSpec).body(body);
                log.info("║ Body applied (type: {})", body.getClass().getSimpleName());
            } else {
                log.info("║ No body to send");
            }

            // ────────────────────────────────────────────────────────────────
            //  Execute
            // ────────────────────────────────────────────────────────────────
            log.info("║ Executing downstream request...");
            String response = requestSpec.retrieve().body(String.class);

            String preview = response != null
                    ? response.substring(0, Math.min(300, response.length())).replace("\n", "\\n")
                    : "<null>";

            log.info("║ Response preview (≤300 chars): {}", preview);
            log.info("╚════════════════════ MCP TOOL CALL END ══════════════════════╝");

            return response != null ? response : "{}";

        } catch (Exception e) {
            log.error("╔══════ TOOL EXECUTION FAILED ══════╗", e);
            log.error("║ Tool   : {}", def.toolName());
            log.error("║ Error  : {}", e.getMessage());
            log.error("╚══════════════════════════════════╝");
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    private boolean isBodySupportingMethod(String method) {
        return "POST".equalsIgnoreCase(method) ||
                "PUT".equalsIgnoreCase(method) ||
                "PATCH".equalsIgnoreCase(method);
    }

    private String extractBearerTokenFromCurrentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                return auth;   // full header forwarded to downstream API
            }
        }
        return null;
    }
}