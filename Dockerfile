FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

COPY . .

RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew shadowJar -x test --no-daemon

CMD ["java", "-jar", "build/libs/logibot-1.0.0-all.jar"]