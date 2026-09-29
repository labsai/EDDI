FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:d0c73262bd5574d0610d6539e235b5b2f67b59981aea395fc2039fca08fc4a40

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
