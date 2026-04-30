FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:cdcdb84d44b34cece97828b19645532b3ba795aa6cbf3dd130a85c57a8c965dc

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
