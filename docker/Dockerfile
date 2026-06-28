# =============================================================================
# USB DeckRec — Multi-stage Docker Build
# =============================================================================
#
# Stage 1: base — JDK 17 + Gradle 8.5 + Android SDK 34 + NDK 26.1
# Stage 2: builder — CI compile with Gradle dep caching
# Stage 3: artifact — Minimal Alpine image with just the APK
#
# Note: apt downloads, Gradle binary, and Android SDK are cached in Docker
# image layers. Gradle dependency and CCache caches are persisted via named
# volumes in docker-compose.yml.
#
# Usage: docker build --target base -t usbdeckrec-base .
#        docker build --target builder -t usbdeckrec-builder .
#        docker build --target artifact -t usbdeckrec-artifact .
#        docker compose -f docker/docker-compose.yml run --rm build

# =============================================================================
# Stage 1: Base — JDK + Gradle + Android SDK + NDK
# =============================================================================
FROM ubuntu:22.04 AS base
ENV DEBIAN_FRONTEND=noninteractive

# Install system dependencies
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget unzip git cmake ninja-build \
    ccache \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Gradle 8.5
ENV GRADLE_VERSION=8.5
RUN wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip \
    -O /tmp/gradle-bin.zip && \
    unzip -q /tmp/gradle-bin.zip -d /opt && \
    ln -sf /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle && \
    rm /tmp/gradle-bin.zip

# Android SDK + NDK
ENV ANDROID_HOME=/opt/android-sdk
RUN wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    -O /tmp/cmdline-tools.zip && \
    mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tmp && \
    mkdir -p ${ANDROID_HOME}/cmdline-tools/latest && \
    mv /tmp/cmdline-tmp/cmdline-tools/* ${ANDROID_HOME}/cmdline-tools/latest/ && \
    rm -rf /tmp/cmdline-tmp /tmp/cmdline-tools.zip

ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

# Accept licenses and install SDK/NDK components
RUN mkdir -p /root/.android && \
    touch /root/.android/repositories.cfg && \
    yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager --install \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        "ndk;26.1.10909125" \
        "cmake;3.22.1" \
        > /dev/null 2>&1 && \
    sdkmanager --update > /dev/null 2>&1

# Enable CCache (speeds up C++/NDK rebuilds ~5-10x)
ENV CCACHE_DIR=/root/.ccache
ENV USE_CCACHE=1
ENV NDK_CCACHE=ccache

WORKDIR /workspace
CMD ["gradle", "--version"]

# =============================================================================
# Stage 2: Builder — Pre-fetches Gradle deps, compiles APK
# =============================================================================
FROM base AS builder
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY app/build.gradle.kts app/proguard-rules.pro app/
RUN gradle --build-cache --parallel dependencies
COPY . .
RUN gradle --build-cache --parallel assembleDebug

# =============================================================================
# Stage 3: Artifact — Extract APK into minimal Alpine image
# =============================================================================
FROM alpine:latest AS artifact
COPY --from=builder /workspace/app/build/outputs/apk/debug/app-debug.apk /apk/
CMD ["cat", "/apk/app-debug.apk"]
