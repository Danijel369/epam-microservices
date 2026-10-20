# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app

# Copy the POM first so dependency resolution is cached independently of source changes.
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy sources and build the executable jar (skip tests to keep image builds fast).
COPY src ./src
RUN mvn clean package -Dmaven.test.skip=true

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Exactly one *.jar is produced; the Spring Boot plugin renames the thin jar to *.jar.original.
COPY --from=build /app/target/*.jar app.jar

# No web interface in this module, so no port is exposed.
CMD ["java", "-jar", "app.jar"]
