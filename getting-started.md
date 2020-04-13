# Getting started

## Version: ≥4.3.x

Welcome to **EDDI**!

This article will help you to get started with **EDDI**.

You have two options to run **EDDI**, The most convenient way is to run **EDDI** with Docker. Alternatively, of course, you can run **EDDI** also from the source by checking out the git repository and build the project with maven using either the `mvn` command line or an IDE such as eclipse.

## Option 1 - EDDI with Docker

There is two ways to use `Docker` with **EDDI**, either with **`docker-compose`** or launch the container manually.

_Prerequisite: You need an up and running `Docker` environment. \(For references, see:_ [https://docs.docker.com/learn/](https://docs.docker.com/learn/)\)

### Use docker-compose \(recommended\)

1. `Checkout` the `docker-compose` file from `Github`:[`https://github.com/labsai/EDDI/blob/master/docker-compose.yml`](https://github.com/labsai/EDDI/blob/master/docker-compose.yml)
2. Run Docker Command:

   ```text
    docker-compose up
   ```

### Use launch docker containers manually

1. Start a `MongoDB` instance using the `MongoDB` `Docker` image:

   ```text
    docker run --name mongodb -e MONGODB_DBNAME=eddi -d mongo
   ```

2. Start **EDDI** :

   ```text
    docker run --name eddi -e "EDDI_ENV=production" --link mongodb:mongodb -p 7070:7070 -d labsai/eddi
   ```

## Option 2 - Run from Source

#### _Prerequisites:_

* `Checkout` from [`Github`](https://github.com/labsai/EDDI)
* `Java 11`
* `Maven 3`
* `MongoDb`
* Download and Install [`MongoDB`](https://www.mongodb.com/) \(Version &gt;= 3.x\)
* Run `MongoDB` on default port 27017 \(this step is important before your run EDDI\):

  **mongod --dbpath \[ABS\_PATH\_TO\_DATA\]**

### Building Project with Maven

* Go to the root directory and execute:

  mvn clean install

> **Important for eclipse users:** If you are planning to browse and build EDDI's code from eclipse, You must take in consideration that EDDI uses project Lombok, so you must add it to eclipse classpath, this can be done easily by executing this jar:`.m2\repository\org\projectlombok\lombok\1.16.18\lombok-1.16.18.jar`

### Start Servers locally from Class File

1 - Launch EDDI's ApiServer class with VM options

```bash
-DEDDI_ENV=[development/production]
```

**Example :**

```bash
-DEDDI_ENV=development
```

2 - Set the working directory to apiserver

3 - Go to the browser --&gt; [`http://localhost:7070`](http://localhost:7070/)

You can overwrite all configs within `EDDI` either by altering the configs itself or - for convenience reasons \(especially when running as the container\) - by passing on VM params.

Example:

```bash
-DEDDI_ENV=[development/production] **-Dmongodb.hosts=somehost** -Duser.dir=[LOCAL_PATH_TO_EDDI]\apiserver ai.labs.api.ApiServer
```

### Start Servers from ZIP

1 - launch EDDI's `ApiServer` from the packaged `ZIP` file `apiserver/target/apiserver-4.3-package.zip` with VM options :

```bash
-DEDDI_ENV=[development/production] -Duser.dir=[LOCAL_PATH_TO_EDDI]\apiserver ai.labs.api.ApiServer
or use .\apiserver\ApiServerStartup.bat resp. ./apiserver/ApiServerStartup.sh
```

2 - Go to Browser --&gt; [http://localhost:7070](http://localhost:8181/)

### Environment Variables

Passing it on to a `Docker` container \(either `plain/docker-compose/kubernetes`\), every environment variable name starting with "`EDDI_JAVA_ENV_`" will be automatically picked up by **EDDI**.

Example:

```bash
EDDI_JAVA_ENV_MONGODB_HOSTS=mongodb.hosts=somehost
```

Would end up as VM param in **EDDI**

```bash
-Dmongodb.hosts=somehost
```

