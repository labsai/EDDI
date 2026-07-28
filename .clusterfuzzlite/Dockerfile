FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:3672cbbeff0c99b255abf3a250d0c11aeba9e1e518278ec384e5c3eebafa8ba1

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
