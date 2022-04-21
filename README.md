# E.D.D.I

Scalable Open Source Chatbot Platform. Build multiple Chatbots with NLP, Behavior Rules, API Connector, Templating.
Developed in Java (with Quarkus), provided with Docker, orchestrated with Kubernetes or Openshift.

v5.0.0 - Beta (current branch)<br>
v4.10.3 - Stable

License: Apache License 2.0

Project website: [here](https://eddi.labs.ai/)

Documentation: [here](http://docs.labs.ai/)

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://www.codacy.com/gh/labsai/EDDI/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=labsai/EDDI&amp;utm_campaign=Badge_Grade) [![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/master.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/master)

## How to run the project

On a terminal, under project root folder, run the following command:

```shell script
./mvnw compile quarkus:dev
```

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package '-Dquarkus.package.type=uber-jar'
```

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

## Build Docker image

```shell script
./mvnw clean package '-Dquarkus.container-image.build=true'
```

Open a browser and navigate to:

```text
http://0.0.0.0:7070/
```
