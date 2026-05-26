FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:d67a6d107709b8d884db8251a488e1f5578268449ddef6ec4b23156b37f8bd35

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
