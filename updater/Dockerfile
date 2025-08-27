# Step 1: Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml first and download dependencies to leverage Docker caching
COPY updater/pom.xml .
# This will download dependencies and fail, but that's expected since we don't have source code yet
RUN mvn dependency:go-offline || true

# Copy source code
COPY updater/src ./src

# Build the application
RUN mvn clean package -DskipTests

# Step 2: Run Stage 
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy the JAR file from the build stage
# The filename should match what's actually generated in your target directory
COPY --from=build /app/target/*.jar updater.jar

# Expose the port the application runs on
EXPOSE 8087

# Use exec form of ENTRYPOINT for proper signal handling
ENTRYPOINT ["java", "-jar", "updater.jar"]