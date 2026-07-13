FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:c06cd9b33ad2cedb2654b552a30e78dd511cef2b4ede8334b33ac33f0bbf2c61

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
