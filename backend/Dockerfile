FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q package -DskipTests

FROM eclipse-temurin:21-jre
# ffmpeg: playback renditions and HEIC/WebP thumbnails (roadmap 2.8). Used when LOCK_TRANSCODE_PROVIDER=ffmpeg.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 lock
USER lock
WORKDIR /app
COPY --from=build /src/target/lock-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
