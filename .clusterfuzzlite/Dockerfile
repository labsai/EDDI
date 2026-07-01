FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:d5abbb261b5387838986c5fabc58c28fd34d0787cdac9a08dd9c98aa0c4eb7b9

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
