# EDDI - Enhanced Dialog Driven Intelligence

A Platform for creating, running and maintaining chatbots of all kinds.

License: Apache License 2.0

## Intro

The Chatbot System - E.D.D.I. (Enhanced Dialog Driven Intelligence), has been developed with the focus on the use in enterprise applications as well as the ease of connecting it to other resources (such as databases or other Services). 

This platform has been developed for over six years and completely restructured from scratch four times because of logical "dead ends" in the art of building chatbots - thus version 4.0-beta.

We are currently working on getting it production ready.
Therefore, consider this as ***BETA STATUS*** for the moment!


The most outstanding features are: 

* it is highly extensible (plugins), 
* very flexible in dialog structure and 
* allows sharing of knowledge between bots

technical spec:

* Resource- / REST-oriented architecture
* Java 8
* JAX-RS 2.0
* Dependency Injection (Guice 4.1)
* Embedded Jetty 9.3
* NoSQL (MongoDB 3.2.8)
* HTML, CSS, Javascript, JSON


## Prerequirements

- Java 8
- Maven 3


## build project with maven
Go to the root directory and execute

    mvn clean install


## Start Servers

1. Import all Modules into Intellij
2. Setup a local mongodb (> v3.0)
2.1 import documents from folder 'mongodb_init'
3. There are two executable main classes that need to be launched (2 Jetty servers)
launch with VM options `-DEDDI_ENV=[development/production] -Duser.dir=[LOCAL_PATH_TO_EDDI]\configurationserver`
    1. io.sls.core.CoreServer
    2. io.sls.configuration.ConfigurationServer
4. Go to Browser --> http://localhost:8181
5. You will be redirect to an single-sign-on server where you can login or register an account
6. You will be redirected back --> http://localhost:8181

## Development

### Keycloak

EDDI requires a Keycloak instance for handling authentication. For development, you might want to have a local Keycloak instance running.
The easiest way to set this up is to use Docker:

```
docker run -d -e KEYCLOAK_USER=admin -e KEYCLOAK_PASSWORD=admin jboss/keycloak
```
NOTE: if you are running this on [docker-machine](https://docs.docker.com/machine/) you need to additionally forward port 8080 to your host. In this case you should instead use the following command:
```
docker run -d -e KEYCLOAK_USER=admin -e KEYCLOAK_PASSWORD=admin -p8080:8080 jboss/keycloak
```
After the first start, connect to Keycloak the Keycloak Administration interface at <keycloak-host>:8080/auth, login with admin/admin and import the EDDI realm from keycloak-dev.json:

1. Add Realm
2. Select file -> keycloak-dev.json
3. Create

Additionally, you have to supply the following system properties (via -D) for running the configserver or the coreserver:

* webserver.keycloak.authServerUrl=http://<host>:<port>/auth
* webserver.keycloak.realmKey=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqki8ITnzytNy+3rPzbrOwSYyA3Jo4+/sf4oUorOuV4ByoW423c/wvYyojq0q/C0HcLLJeCpWzHlC7xvUrPhofVWEQDUrhAo1KNIoW8Fw9LfyFzaUtwMJ3hdRxYQ4gMHERv8IOLd4fZqTIiV2taxE3/nCR5D6WqAjTBvAoh2a69HJ6Ln+TUwCa5uMC/d3NPhsjSERA3ABLwlYKZD+VhngttqN4m8US32ayP9A5lCoM/uVcTb9CJl754VsjcwTybHQpWLbCdOu4SH0b7y6VcSNUJDCNeDkl8JJ84YG6prxDS8/SKTYFZKUxxvdYJ/Lm0HRPEqCpy+snZVApV9MQd7rOQIDAQAB

If your Keycloak URL is different from http://localhost:8080/auth then you need to change configurationserver/resources/keycloak/development/keycloak-adapter.json accordingly.

### MongoDB
```
docker run --name some-mongo -d mongo --auth
```
NOTE: Again, in case of docker-machine:
```
docker run --name eddi-mongo -d -p27017:27017 mongo --auth

docker exec -it eddi-mongo mongo admin
db.createUser({ user: 'admin', pwd: 'adminpass', roles: [ { role : "userAdminAnyDatabase", db : "admin"} ] });
exit

docker exec -it eddi-mongo mongo -u admin -p adminpass --authenticationDatabase admin eddi
db.createUser({ user: 'eddi', pwd: 'eddipass', roles: [ { role : "readWriteAnyDatabase", db : "admin"} ] });
exit

docker exec -i eddi-mongo mongoimport --db eddi --collection extensions -u eddi -p eddipass --jsonArray < mongodb_init/extensions.json
docker exec -i eddi-mongo mongoimport --db eddi --collection descriptors -u eddi -p eddipass --jsonArray < mongodb_init/descriptors.json
docker exec -i eddi-mongo mongoimport --db eddi --collection permissions -u eddi -p eddipass --jsonArray < mongodb_init/permissions.json
```

Additionally, you have to supply the following system properties (via -D) for running the configserver or the coreserver:

* mongodb.hosts=<host> (if <host> is different from localhost)
* mongodb.password=eddipass

### REST

All REST Interfaces start with 'IRest' as IRestBotAdministration for instance

### Rest Entry Points
io.sls.core.rest.IRestBotAdministration
io.sls.core.rest.IRestBotEngine

### Dependency Injection

Wanna add some classes? Use Dependency Injection via Google Guice.
The main methods are the appropriate places to initialize custom modules.

## Documentation

Bot states: 

* green - editable
* red - previous version

A Bot constists of packages.

A package has a lifecycle and contains plugins/extensions (e.g. Input Parser plugin, Dialog/Behaviour Rules plugin, Output plugin).

Plugins can contain other plugins and interfaces such as plugin "Dictionaries" can contain different dictionaries.

A dictionary classifies input (phrases and terms) and can also be an NLP dictionary where different sentence elements are parsed.

Dialog/Behaviour Rules is a group of rules:

* checks conditions (can also be plugins)
* if true defines which action should be executed next and sets the action in the analysis session (conversation memory / state)

### REST API

IRestBotEngine: interact with bot

* create new conversation
POST /bots/{environment}/{botId}
-> returns /{environment}/{botId}/{conversationId}

* talk to bot
POST /{environment}/{botId}/{conversationId}


IRestBotAdministration: deploy bot

## relevant packages ordered by application flow  

Main Bot Communication Endpoint
```java
io.sls.core.ui.rest.*
```

Bootstrapping/Deploying
```java
io.sls.core.runtime.*
```

The conversation lifecycle between the bot and the human
```java
io.sls.core.lifecycle.*
```

The one and only user specific (conversation) state in the whole application.
It is used for communication between the plugins.
```java
io.sls.memory.*
```

Prepares the user input for the parser.
```java
io.sls.core.normalizing.*
```

Parses the input of the user and translates it to "Expressions", based on dictionaries and correction algorithms. Expression are meanings, e.g. day after tomorrow -> date(today+1), yes -> confirmation(true)
Dictionaries and Corrections are plugins and therefore can be extended.
```java
io.sls.core.parser.*
```

There are groups of Rules. Each rules holds an list of conditions that all need to pass true in order for the rule to be successful. As soon as one rule has passed true none of the other following group members is executed. Successful rules have a set of "actions" that should be executed later on. 
Conditions are plugins, thus extendable. 
```java
io.sls.core.behavior.*
```

Based on the actions an matching output will be selected. 
```java
io.sls.core.output.*
```

----------------

Auto testing bot conversations, kind of integration testing
```java
io.sls.testing.*
```

Rest interfaces
```java
io.sls.resources.rest.*
```

Data storage
```java
io.sls.persistence.*
```

Authorization for chatbot resources (dictionaries, packages, behavior rules, etc.)
```java
io.sls.permission.*
``` 

### Application Documents
All configurations in EDDI are stored as separate JSONs and are versioned on every change that will be made.

Therefore each JSON has the following properties:

    _id
        Is the unique identifier for the specific resource and will be used for reference
    _version
        Is an integer and is used to version every change made to any configuation within EDDI

Both properties will be assigned internally and therefore are prefixed with an underscore. Do not change these values as this will result in data inconsistency.

Bots of configurations on how a bot should react to inputs from the users or certain events.

Bots contain (Knowledge-)Packages. A JSON of a bot may look like this:

---------------------------

```json
{

    "_id":"51059a82e4b087c8e3554bce",

    "_version" : 1

    "packages" : [

        "resource://io.sls.package/packagestore/packages/51059b15e4b087c8e3554bd1?version=1"

    ],

    "authenticationRequired" : true

}
```

--------------------------

A Bot-Config ist a simple JSON containing two mandatory properties:

    packages
        An array of URI references to the package configs
    authenticationRequired
        A boolean param indicating whether this bot should be available to public or an user authentication will be required

Documentation is in ongoing progress and will be extended shortly...