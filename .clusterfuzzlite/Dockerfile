FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:7e5ece45a45d05aa4df04b7a73a955bdf065227f9592fce52599558394b44572

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
