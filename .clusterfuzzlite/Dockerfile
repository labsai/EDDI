FROM gcr.io/oss-fuzz-base/base-builder-jvm

# Install Maven (project uses Maven wrapper, but ClusterFuzzLite needs mvn on PATH)
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

COPY . $SRC/project
WORKDIR $SRC/project
COPY .clusterfuzzlite/build.sh $SRC/
