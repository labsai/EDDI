# EDDI - Enhanced Dialog Driven Intelligence

Scalable Open Source Chatbot Platform. Build multiple Chatbots with NLP, Behavior Rules, API Connector, Templating. 

v4.8.0 - IN DEVELOPMENT

v4.7.1 - STABLE

License: Apache License 2.0

Visit [here](https://eddi.labs.ai/) for further references about the project.

For professional support, check out: [here](https://www.labs.ai/)

Check out the full documentation [here](http://docs.labs.ai/).

[![Codacy Badge](https://api.codacy.com/project/badge/Grade/17f0bf1c32b346fc9232e7601327d212)](https://app.codacy.com/app/ginccc/EDDI?utm_source=github.com&utm_medium=referral&utm_content=labsai/EDDI&utm_campaign=Badge_Grade_Dashboard)
[![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/master.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/master)

## Why choose E.D.D.I over other technologies
### We have asked that question to our users and here are some reasons they mentioned
* it is open source 💚
* the configuration is extremely open and flexible
* the code is self-explanatory and easy to extend
* eddi is channel independent, therefore can be used anywhere
* the (NLP) parser is extremely cool
* the framework is focused and does not pretend to be the magic bullet that solves it all
* great tech stack, when you want to build a chatbot product yourself

## Intro

The Chatbot System - E.D.D.I (Enhanced Dialog Driven Intelligence), 
has been developed with the focus on the use in enterprise applications as well as 
the ease of connecting it to other resources (such as databases or other Services). 

This platform has been developed for over many years and completely restructured from scratch four times 
because of logical "dead ends" in the art of building chatbots - thus version 4.

The most outstanding features are:
* Flexible in NLP and Behavior
* Fluently connect to REST APIs
* Powerful Templating
* Reuse Conversation Flows in multiple bots

technical spec:
* Resource- / REST-oriented architecture
* OAuth 2.0 / Basic Authentication
* Java
* JAX-RS
* Dependency Injection
* Embedded Jetty
* NoSQL
* HTML, CSS, Javascript, JSON

## Prerequirements

- Java 11
- Maven 3

## Build project with maven
Go to the root directory and execute

    mvn clean install

## Start Servers
1. Setup a local mongodb (> v3.0)
2. launch with VM options 
   ```
    -DEDDI_ENV=[development/production] -Duser.dir=[LOCAL_PATH_TO_EDDI]\apiserver ai.labs.api.ApiServer
   ```
3. Go to Browser --> http://localhost:7070

Note: If running locally inside an IDE you need *lombok* to be enabled (otherwise you will get compile errors complaining about missing constructors). Either download as plugin (e.g. inside Intellij) or follow instructions here https://projectlombok.org/

## Docker

For development, use

```
docker-compose -f docker-compose.yml -f docker-compose.local.yml up
```

after running `mvn package`. This builds a local image of EDDI.

For integration testing run 
```
./integration-tests.sh
```
or
```
docker-compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.testing.yml -p ci up -d
```

For running in cloud (with authentication), check: https://hub.docker.com/r/labsai/eddi
