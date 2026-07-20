FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:1706e0c5db90b31ea06268bfb0ba89eafaf80220d784304790f2d2529084d499

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
