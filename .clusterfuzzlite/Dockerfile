FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:a3652934a4580fc62e1798b15ae257b7740a2815c4fcf1bd631b5a9743780e62

# No Maven or JDK 25 needed — build.sh compiles only the 3 vendored
# utility classes with javac. JDK version is auto-detected (17 or 21).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
