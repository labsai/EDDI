FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:09b8394f9b11bec7b8b40e3a0ba72335f699ccad39eb98ca0be792a5d26af2a1

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
