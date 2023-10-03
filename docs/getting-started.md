# Getting started

**Version: ≥5.0.x**

Welcome to **EDDI**!

This article will help you to get started with **EDDI**.

You have two options to run **EDDI**, The most convenient way is to run **EDDI** with Docker. Alternatively, of course, you can run **EDDI** also from the source by checking out the git repository and build the project with maven using either the `mvn` command line or an IDE such as eclipse.

## Option 1 - EDDI with Docker

There are two ways to use `Docker` with **EDDI**, either with **`docker-compose`** or launch the container manually.

_**Prerequisite**: You need an up and running `Docker` environment. (For references, see:_ [https://docs.docker.com/learn/](https://docs.docker.com/learn/))

### Use docker-compose (recommended)

1. `Checkout` the `docker-compose` file from `Github`:[`https://github.com/labsai/EDDI/blob/master/docker-compose.yml`](https://github.com/labsai/EDDI/blob/master/docker-compose.yml)
2.  Run Docker Command:

    ```
     docker-compose up
    ```

### Use launch docker containers manually

1.  Create a shared network

    ```
    docker network create eddi-network
    ```
2.  Start a `MongoDB` instance using the `MongoDB` `Docker` image:

    ```
    docker run --name mongodb --network=eddi-network -d mongo
    ```
3.  Start **EDDI** :

    ```
    docker run --name eddi --network=eddi-network -p 7070:7070 -d labsai/eddi
    ```

## Option 2 - Run from Source

#### _Prerequisites:_

* Java 17
* Maven 3.8.4
* MongoDB > 4.0

### How to run the project

Setup a local mongodb (> v4.0)

{% hint style="info" %}
If no mongodb instance is available on the give host, quarkus will try to run a mongodb container on startup, given the host has a docker running server
{% endhint %}

On a terminal, under project root folder, run the following command:

```shell
./mvnw compile quarkus:dev
```

1. Go to Browser --> [http://localhost:7070](http://localhost:7070)

Note: If running locally inside an IDE you need _lombok_ to be enabled (otherwise you will get compile errors complaining about missing constructors). Either download as plugin (e.g. inside Intellij) or follow instructions here \[https://projectlombok.org/]\(https://projectlombok.org/

### Build App & Docker image

```bash
./mvnw clean package '-Dquarkus.container-image.build=true'
```

### Download from Docker hub registry

```bash
docker pull labsai/eddi
```

[https://hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

### Run Docker image

For production, launch standalone mongodb and then start an eddi instance as defined in the docker-compose file

```bash
docker-compose up
```

For development, use

```bash
docker-compose -f docker-compose.yml -f docker-compose.local.yml up
```

For integration testing run

```bash
./integration-tests.sh
```

or

```bash
docker-compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.testing.yml -p ci up -d
```

> **Important for eclipse users:** If you are planning to browse and build EDDI's code from eclipse, you must take in consideration that EDDI uses project Lombok, so you must add it to eclipse classpath, this can be done easily by executing this jar:`.m2\repository\org\projectlombok\lombok\1.16.26\lombok-1.16.26.jar`

