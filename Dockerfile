FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

COPY . .

RUN ./gradlew build -x test

CMD ["./gradlew", "run"]