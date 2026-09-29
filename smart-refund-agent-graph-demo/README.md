# smart-refund-agent-graph-demo

Spring Boot 3.x + Java 21 + Gradle scaffold for refund workflow development.

## What is included

- Spring Boot web and validation setup
- Actuator health/info endpoints
- Configuration properties for agent graph settings
- Application/service/port/infrastructure package structure
- A local stub adapter that can be replaced by a real LangGraph adapter later

## Quick start

```bash
./gradlew test
./gradlew bootRun
```

## Main entry points

- `src/main/java/com/zc/smartrefundagentgraphdemo/application`
- `src/main/java/com/zc/smartrefundagentgraphdemo/infrastructure`
- `src/main/java/com/zc/smartrefundagentgraphdemo/interfaceadaptor`
