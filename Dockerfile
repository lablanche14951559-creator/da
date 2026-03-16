FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

COPY . .

# В git на Windows gradlew часто без executable-бита и/или с CRLF.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew build -x test

CMD ["./gradlew", "run"]