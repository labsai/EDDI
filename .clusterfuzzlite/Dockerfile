FROM gcr.io/oss-fuzz-base/base-builder-jvm

# No Maven or JDK 25 needed — build.sh compiles only the 2 utility
# classes directly with javac (targeting Java 17 to match the runtime JVM).

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
