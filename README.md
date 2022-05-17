<div align="center">
<img src="https://repository-images.githubusercontent.com/70809374/dda376bd-774b-49cf-8b8e-f29378133b42" align="center" style="width: 100%" />
</div>  

# E.D.D.I

Scalable Open Source Chatbot Platform. Build multiple Chatbots with NLP, Behavior Rules, API Connector, Templating.
Developed in Java (with Quarkus), provided with Docker, orchestrated with Kubernetes or Openshift.

v5.0.0 - Stable

License: Apache License 2.0

Project website: [here](https://eddi.labs.ai/)

Documentation: [here](http://docs.labs.ai/)

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://www.codacy.com/gh/labsai/EDDI/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=labsai/EDDI&amp;utm_campaign=Badge_Grade) [![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/master.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/master)

## Intro

The Chatbot System - E.D.D.I \(Enhanced Dialog Driven Intelligence\),
has been developed with the focus on the use in enterprise applications as well as the ease of connecting it
to other resources \(such as databases or other Services\).

The most outstanding features are:

* Flexible in NLP and Behavior
* Fluently connect to REST APIs
* Powerful Templating
* Reuse Conversation Flows in multiple bots

technical spec:

* Resource- / REST-oriented architecture
* OAuth 2.0 / Basic Authentication (Keycloak)
* Java Quarkus
* JAX-RS
* Dependency Injection
* NoSQL (MongoDB)
* HTML, CSS, Javascript, JSON

## Prerequirements

* Java 17
* Maven 3.8.4
* MongoDB > 4.0

## How to run the project

1. Setup a local mongodb \(&gt; v4.0\)
2. On a terminal, under project root folder, run the following command:

```shell script
./mvnw compile quarkus:dev
```

3. Go to Browser --&gt; [http://localhost:7070](http://localhost:7070)

Note: If running locally inside an IDE you need _lombok_ to be enabled \(otherwise you will get compile errors
complaining about missing constructors\). Either download as plugin \(e.g. inside Intellij\) or follow instructions
here [https://projectlombok.org/](https://projectlombok.org/

## Build App & Docker image

```bash
./mvnw clean package '-Dquarkus.container-image.build=true'
```

## Download from Docker hub registry

```bash
docker pull labsai/eddi
```

[https://hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

## Run Docker image

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
