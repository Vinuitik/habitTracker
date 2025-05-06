# Use an official OpenJDK runtime as a parent image
FROM openjdk:21-jdk-slim

# Set the working directory inside the container
WORKDIR /app

# Copy the built JAR file into the container
COPY updater/target/updater-1.0.0.jar updater.jar

# Expose the port the application runs on
EXPOSE 8087

# Command to run the application
ENTRYPOINT ["java", "-jar", "updater.jar"]