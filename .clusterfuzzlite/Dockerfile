FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:6d8d75febebc2fe98d2ecb426eba503afdd9e310bdb249c37470ce4950c0d83b

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
