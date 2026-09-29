FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:dfd607cb791f694f8300dd2e61efceded84be7989b300db29fadb2506d6a1ab1

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
