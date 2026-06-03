FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:6eb178da9e041e6eed158e3995409ae3a56c4bf9644c8cf200fcb9577b7ad13d

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
