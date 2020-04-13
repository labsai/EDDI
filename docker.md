# Docker

## Setup

1. Option: use docker-compose \(recommended\) 1. Checkout the docker-compose file from github: [https://github.com/labsai/EDDI/blob/master/docker-compose.yml](https://github.com/labsai/EDDI/blob/master/docker-compose.yml) 2. Run Docker Command:

   docker-compose up

2. Option: launch docker containers manually

Start a MongoDB instance using the MongoDB docker image:

```bash
docker run --name mongodb -e MONGODB_DBNAME=eddi -d mongo
```

Start EDDI:

## No Authentication

```bash
docker run --name eddi -e "EDDI_ENV=production" --link mongodb:mongodb -p 7070:7070 -p 7443:7443 -d labsai/eddi
```

## Basic Authentication Enabled

\(default username: eddi , default password: labsai\):

```bash
docker run --name eddi -e "EDDI_ENV=production" -e "EDDI_JAVA_ENV_BASIC_AUTH_ENABLED: webServer.securityHandlerType=basic" --link mongodb:mongodb -p 7070:7070 -p 7443:7443 -d labsai/eddi
```

## Basic Authentication Enabled \(set credentials\)

\(set credentials on first run\):

```bash
docker run --name eddi -e "EDDI_ENV=production" -e "EDDI_JAVA_ENV_BASIC_AUTH_ENABLED: webServer.securityHandlerType=basic" -e "EDDI_JAVA_ENV_BASIC_AUTH_USERNAME: webServer.webServer.basicAuth.defaultUsername=eddi" -e "EDDI_JAVA_ENV_BASIC_AUTH_PASSWORD: webServer.webServer.basicAuth.defaultPassword=labsai" --link mongodb:mongodb -p 7070:7070 -p 7443:7443 -d labsai/eddi
```

## Keycloak Enabled

\(default authenticates over [auth.labs.ai](http://auth.labs.ai/) keycloak instance\):

```bash
docker run --name eddi -e "EDDI_ENV=production" -e "EDDI_JAVA_ENV_KEYCLOAK_ENABLED: webServer.securityHandlerType=keycloak" --link mongodb:mongodb -p 7070:7070 -p 7443:7443 -d labsai/eddi
```

When E.D.D.I is up and running:

* **Overview over API:** [**http://localhost:7070/view**](http://localhost:7070/view) **or \[**[**https://localhost:7443/view\]\(https://localhost:7443/view**](https://localhost:7443/view]%28https://localhost:7443/view)\)
* **Chat with bot:** [**http://localhost:7070/chat/unrestricted/{botId}**](http://localhost:7070/chat/unrestricted/%7BbotId%7D) **or \[**[**https://localhost:7443/chat/unrestricted/{botId}\]\(https://localhost:7443/chat/unrestricted/%7BbotId%7D**](https://localhost:7443/chat/unrestricted/{botId}]%28https://localhost:7443/chat/unrestricted/%7BbotId%7D)\)

