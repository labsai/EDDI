FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:587f1e08cdebabb3d5fc37df80cefaa70a0c372fe3880d4e77cc274ff9142dc9

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
