FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:5e6c5595cb4421b1cc3b71005aaaf5392f35df5dce402f1ef109c6c1b19428b5

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
