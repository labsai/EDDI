# EDDI - Enhanced Dialog Driven Intelligence

[![Join the chat at https://gitter.im/EDDI-labs-ai/Lobby](https://badges.gitter.im/EDDI-labs-ai/Lobby.svg)](https://gitter.im/EDDI-labs-ai/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

A Platform for creating, running and maintaining chatbots of all kinds.

v4.0.0

License: Apache License 2.0

Visit http://eddi.labs.ai for further references.

Check out the full documentation [here](https://labsai.atlassian.net/wiki/display/EDDI/).

[![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/master.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/master)

## Intro

The Chatbot System - E.D.D.I. (Enhanced Dialog Driven Intelligence), 
has been developed with the focus on the use in enterprise applications as well as 
the ease of connecting it to other resources (such as databases or other Services). 

This platform has been developed for over six years and completely restructured from scratch four times 
because of logical "dead ends" in the art of building chatbots - thus version 4.0.0.

The most outstanding features are: 
* it is highly extensible (plugins), 
* very flexible in dialog structure and 
* allows sharing of knowledge between bots

technical spec:
* Resource- / REST-oriented architecture
* Java
* JAX-RS
* Dependency Injection
* Embedded Jetty
* NoSQL
* HTML, CSS, Javascript, JSON


## Prerequirements

- Java 8
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

## Docker

For development, use

```
docker-compose -f docker-compose.yml -f docker-compose.local.yml up
```

after running `mvn package`. This builds a local image of EDDI.