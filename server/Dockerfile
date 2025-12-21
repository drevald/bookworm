FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

# Step 1: Copy only dependency configs (cached unless these change)
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Step 2: Download dependencies (cached layer)
RUN gradle dependencies --no-daemon --no-build-cache

# Step 3: Copy proto files (cached unless proto changes)
COPY src/main/proto ./src/main/proto
RUN gradle extractIncludeProto extractProto generateProto --no-daemon

# Step 4: Copy source code (this layer invalidates often)
COPY src ./src

# Step 5: Build (reuses previous layers if source hasn't changed)
RUN gradle build --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update && \
    apt-get install -y tesseract-ocr \
    libgl1 libglib2.0-0 libsm6 libxext6 libxrender-dev && \
    rm -rf /var/lib/apt/lists/*

COPY tessdata /app/tessdata
ENV TESSDATA_PREFIX=/app/tessdata/

ENV JAVA_TOOL_OPTIONS=""

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
