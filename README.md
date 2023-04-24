# E.D.D.I

E.D.D.I (Enhanced Dialog Driven Interface) is an enterprise-certified chatbot middleware that offers configurable NLP,
Behavior Rules, and API connectivity for seamless integration with various conversational services.

Developed in Java (with Quarkus), provided with Docker, orchestrated with Kubernetes or Openshift.

Latest stable version: 5.0.4

License: Apache License 2.0

Project website: [here](https://eddi.labs.ai/)

Documentation: [here](https://docs.labs.ai/)

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://www.codacy.com/gh/labsai/EDDI/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=labsai/EDDI&amp;utm_campaign=Badge_Grade) [![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/main.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/main)

## Intro

E.D.D.I is a highly scalable and enterprise-certified cloud-native chatbot middleware
that serves as a trusted gatekeeper for various conversational services. Designed to run smoothly in cloud environments
like Docker, Kubernetes, and Openshift, E.D.D.I offers configurable NLP and Behavior Rules that facilitate conversations
and can act as watchdog for sensitive topics. Its API integration capabilities make it easy to connect
with other conversational or classical REST APIs. Multiple bots can be easily integrated and run side by side,
including multiple versions of the same bot for a smooth upgrading transition.

Features worth mentioning:

* Easily integrate other conversational or classical REST APIs
* Configurable NLP and Behavior rules allow conversation facilitation as well as watch dog for sensitive topics
* Multiple bots could be easily integrated and run side by side even multiple versions of the same bot 

technical spec:

* Resource- / REST-oriented architecture
* Java Quarkus
* JAX-RS
* Dependency Injection
* Prometheus integration (Metrics endpoint)
* Kubernetes integration (Liveness/Readiness endpoint)
* MongoDB for storing bot configurations and conversation logs
* OAuth 2.0 (Keycloak) for authentication and user management
* HTML, CSS, Javascript (Dashboard & Basic Chat UI)

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

## prometheus/metrics integration


```bash
<eddi-instance>/q/metrics
```

## kubernetes integration

Liveness endpoint:
```bash
<eddi-instance>/q/health/live
```

Readiness endpoint:
```bash
<eddi-instance>/q/health/ready
```
