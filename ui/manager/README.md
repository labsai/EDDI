# EDDI-CONFIG-UI

## Setup

```
npm install
```

## Development

Run `npm start` to run the packager and have the app run in your default browser.

## Build

```
[NODE_ENV=<*development*,staging,production>] npm run build
```

### Run Docker Compose

Build eddi-config-ui local docker image:

```
docker-compose -f docker-compose.yml -f docker-compose.local.yml -p config-local build
```

Run docker-compose with locally build image:

```
docker-compose -f docker-compose.yml -f docker-compose.local.yml -p config-local up -d
```

Shutting down locally build eddi-config-ui:

```
docker-compose -f docker-compose.yml -f docker-compose.local.yml -p config-local down
```

Run latest eddi-config-ui from docker-hub:

```
docker-compose up
```

### Run the Docker locally

This will run the docker image locally.

```
docker run -p "7071:7071" name_of_container
```

You can change the environments by using this command.
Change `EDDI_API_URL=localhost:7070` to whatever path you like.

```
docker run -e  "EDDI_API_URL=localhost:7070" -p "7071:7071" name_of_container
```

This image is suppose to work with [**EDDI**](https://github.com/labsai/EDDI).
You will need it to be running in the background with bots added to it, in order for it to run properly
