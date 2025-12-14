# Auth BFF (Backend for Frontend) Project Overview

## Project Purpose
This is a Spring Boot application that serves as a Backend for Frontend (BFF) for handling authentication flows with Keycloak. It provides OAuth2 integration, Redis-based session management, and RESTful authentication endpoints for frontend applications.

## Tech Stack
- **Framework**: Spring Boot 3.5.6
- **Java Version**: 17
- **Build Tool**: Gradle
- **Authentication**: Spring Security with OAuth2 Client (Keycloak)
- **Session Storage**: Redis with Spring Session
- **Libraries**: 
  - Lombok for boilerplate reduction
  - Spring WebFlux
  - Spring Security Test for testing

## Key Features
- Keycloak OAuth2 integration with Authorization Code flow
- Redis-based session management
- RESTful authentication endpoints
- CORS configuration for frontend integration
- Secure cookie handling (HttpOnly, Secure, SameSite)
- Token refresh functionality

## Application Structure
- **Controller Layer**: `AuthController` handles all authentication endpoints
- **Configuration**: 
  - `SecurityConfig` - OAuth2 and security settings
  - `RedisConfig` - Redis session management
- **Service Layer**: `TokenService` for token management
- **Main Application**: `ApiGatewayBffApplication` (Spring Boot main class)

## Endpoints
- `GET /auth/login` - Get authentication status and user info with access token
- `POST /auth/logout` - Logout and clear session
- `GET /auth/user` - Get current user information
- `POST /auth/refresh` - Refresh access token
- `GET /auth/health` - Health check endpoint