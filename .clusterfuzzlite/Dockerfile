FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:cc3c3da52c2f48d7a6caf1d5736ab8ec8cb213f4345329b33cb6e497780b1ed0

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
