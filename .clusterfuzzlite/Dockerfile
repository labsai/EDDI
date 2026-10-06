FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:48d43fdfa2cd4ba98214adbe5d3659d4d60fef78ca66da8ffaf9a4a57e67a53d

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
