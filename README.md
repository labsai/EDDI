# E.D.D.I: Prompt & Conversation Management Middleware for Conversational AI APIs

E.D.D.I (Enhanced Dialog Driven Interface) is a middleware to connect and manage LLM API bots 
with advanced prompt and conversation management for APIs such as OpenAI ChatGPT, Facebook Hugging Face, 
Anthropic Claude, Google Gemini and Ollama 

Developed in Java using Quarkus, it is lean, RESTful, scalable, and cloud-native. 
It comes as Docker container and can be orchestrated with Kubernetes or Openshift.
The Docker image has been certified by IBM/Red Hat.

Latest stable version: 5.3.1

License: Apache License 2.0

Project website: [here](https://eddi.labs.ai/)

Documentation: [here](https://docs.labs.ai/)

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://app.codacy.com/organizations/gh/labsai/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=labsai/EDDI&amp;utm_campaign=Badge_Grade) [![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/main.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/main)

![alt text](https://eddi.labs.ai/EDDI-landing-page-image.png)

## Overview

E.D.D.I is a high performance middleware for managing conversations in AI-driven applications. 
It is designed to run efficiently in cloud environments such as Docker, Kubernetes, and Openshift. 
E.D.D.I offers seamless API integration capabilities, allowing easy connection with various conversational services or 
traditional REST APIs with runtime configurations. 
It supports the integration of multiple chatbots, even multiple versions of the same bot, for smooth upgrading and transitions.

Notable features include:

* Seamless integration with conversational or traditional REST APIs
* Configurable NLP and Behavior rules to orchestrate LLM involvement
* Support for multiple chatbots, including multiple versions of the same bot, running concurrently
* Support for Major AI API integrations via langchain4j: OpenAI, Hugging Face (text only), Claude, Gemini, Ollama (and more to come)

Technical specifications:

* Resource-/REST-oriented architecture
* Java Quarkus framework
* JAX-RS
* Dependency Injection
* Prometheus integration (Metrics endpoint)
* Kubernetes integration (Liveness/Readiness endpoint)
* MongoDB for storing bot configurations and conversation logs
* OAuth 2.0 (Keycloak) for authentication and user management
* HTML, CSS, Javascript (Dashboard)
* React (Basic Chat UI)

## Prerequisites

* Java 21
* Maven 3.8.4
* MongoDB >= 5.0

## How to run the project

1. Setup a local mongodb \(&gt; v5.0\)
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
