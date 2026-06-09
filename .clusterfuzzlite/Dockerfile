FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:2e91cf2477da1f0c1d0ac71af4f9d11024179cf74f85949a360b001d37b95fbb

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
