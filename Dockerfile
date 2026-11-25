# Stage 1: Build the Spring Boot JAR with Maven and Java 21
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# Stage 2: Runtime image with Java 21, FFmpeg, and yt-dlp
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        python3 \
        nodejs \
        curl \
        ca-certificates \
    && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/universal-video-downloader-1.0.0.jar app.jar

ENV PORT=8080
ENV YTDLP_PATH=/usr/local/bin/yt-dlp
ENV COOKIE_FILE=/tmp/cookies.txt

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
