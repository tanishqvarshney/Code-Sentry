# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build

# Copy Maven descriptor and source code
COPY pom.xml .
COPY codesentry-core/pom.xml codesentry-core/
COPY codesentry-core/src codesentry-core/src/
COPY codesentry-app/pom.xml codesentry-app/
COPY codesentry-app/src codesentry-app/src/
COPY codesentry-benchmark/pom.xml codesentry-benchmark/
COPY codesentry-benchmark/src codesentry-benchmark/src/

# Package the application skipping tests
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy packaged jar from builder stage
COPY --from=builder /build/codesentry-app/target/codesentry-app-1.0-SNAPSHOT.jar codesentry.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "codesentry.jar"]
