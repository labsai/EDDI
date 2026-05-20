FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:a006f3fd87df4f12d0c70ee2204e8bdb0b1b2eef00b12218e880d36d6406a624

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
