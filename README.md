# OpenAPI → MCP Server Bridge

A **generic, loosely-coupled** Spring Boot application that converts **any OpenAPI/Swagger specification** into MCP (Model Context Protocol) tools automatically.

When an MCP client (Cursor, Claude Desktop, MCP Inspector, custom agents, etc.) calls one of these tools, the server executes the corresponding REST API call — forwarding Bearer tokens, headers, path/query/body parameters as configured.

**Main goals:**
- Turn any OpenAPI 3.x spec into discoverable MCP tools
- Secure forwarding of authentication (Bearer tokens)
- Excellent observability (structured logging at every step)
- Loosely coupled & extensible architecture

## Features

- Automatic parsing of multiple OpenAPI specs (local files or remote URLs) at startup
- Each operation becomes one MCP tool (using `operationId` or generated name)
- Full support for:
  - Path / Query / Header / Cookie parameters
  - Request body (as `"body"` argument)
  - Bearer token forwarding (from MCP client → downstream API)
- Heavy debug logging for token propagation & request building
- Designed to be secure (Keycloak-ready OAuth2 resource server skeleton included)
- Spring AI MCP server (streamable-http protocol)
- Compatible with MCP Inspector, Cursor, Claude, etc.

## Tech Stack (2026 state)

- Java 21
- Spring Boot 4.0.3
- Spring AI 2.0.0-M2 (MCP server starter)
- swagger-parser 2.1.38
- Jackson (for schema generation & JSON handling)
- RestClient (Spring 6+ modern HTTP client)
- Lombok
- SLF4J + structured logging

## Project Structure
