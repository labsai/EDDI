# Docker

## Setup

1. **Option**: Use docker-compose (recommended)&#x20;
   1. 1\. Checkout the docker-compose file from github: [https://github.com/labsai/EDDI/blob/master/docker-compose.yml](https://github.com/labsai/EDDI/blob/master/docker-compose.yml)
      1. 2\. Run Docker Command:`docker-compose up`
2. **Option**: Launch docker containers manually

Start a MongoDB instance using the MongoDB docker image:

```bash
docker run --name mongodb -e MONGODB_DBNAME=eddi -d mongo
```

Start EDDI:

```bash
docker run --name eddi --link mongodb:mongodb -p 7070:7070 -d labsai/eddi
```
