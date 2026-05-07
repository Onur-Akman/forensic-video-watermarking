# ============================================
# Stage 1: Build the application
# ============================================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies first (Docker layer caching)
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# ============================================
# Stage 2: Run the application
# ============================================
FROM eclipse-temurin:17-jre

# Install curl for healthcheck
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built jar
COPY --from=builder /build/target/*.jar app.jar

# Create upload directory
RUN mkdir -p /app/uploads

EXPOSE 8082

ENTRYPOINT ["java", \
    "-Xms512m", "-Xmx2560m", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=200", \
    "-jar", "app.jar"]

