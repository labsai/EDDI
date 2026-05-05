FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:bb13970c9d56f3adba4e8979a0e7de25db8e546889a34b533d27cddae5807505

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
