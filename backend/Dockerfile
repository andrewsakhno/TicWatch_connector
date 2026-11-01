# Build context = repo root (the backend is a subproject of the root Gradle build).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/
RUN chmod +x gradlew && ./gradlew --no-daemon :backend:dependencies > /dev/null
COPY backend/src backend/src
RUN ./gradlew --no-daemon :backend:bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/backend/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8766
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
