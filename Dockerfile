FROM openjdk:17-jdk-slim
WORKDIR /app

# Копируем JAR и keystore
COPY app.jar app.jar

CMD ["java", "-jar", "app.jar"]