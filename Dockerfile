# syntax=docker/dockerfile:1
#
# Reproducible build environment for the HandsFree APK.
# The app itself runs on an Android phone, not in a container. This image
# holds the exact JDK, Android SDK and Gradle versions the APK is built with.
#
#   docker build --target apk --output out .
#
# The debug APK is written to ./out/. See README.md, "Build with Docker".

FROM eclipse-temurin:17-jdk-jammy AS build

ARG CMDLINE_TOOLS=14742923
ARG MODELS_URL=https://github.com/JCDC0/HandsFree/releases/download/models-v1

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/opt/gradle-home
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip git ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" > /dev/null

WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon --version

COPY . .
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# The Whisper ONNX files are too large for git. Use local copies when the
# build context has them; otherwise download them from the GitHub release.
RUN set -e; dir=app/src/main/assets/whisper; mkdir -p $dir; cd $dir; \
    for f in encoder.onnx decoder.onnx tokens.txt; do \
      [ -s "$f" ] || curl -fsSL -o "$f" "$MODELS_URL/$f"; \
    done; \
    printf '%s\n' \
      "5e542bcebd85d9d61a73bc984d7bb17cb599c6061d19133b9627a8d51618cb5d  encoder.onnx" \
      "0c630776d7c2f94088c40e06668254e2b00ed04caaa5321ac97cc82ed029fb04  decoder.onnx" \
      "febeed8e568f92d9ca984580bc2e6b605b867dc5ba4486f9646de381b44a8226  tokens.txt" \
      | sha256sum -c -

RUN ./gradlew --no-daemon assembleDebug

FROM scratch AS apk
COPY --from=build /src/app/build/outputs/apk/debug/ /
