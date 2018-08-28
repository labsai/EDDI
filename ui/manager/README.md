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

### Pull Docker
In order to pull the docker image, you need Google Shell. It can be downloaded[**here**](https://cloud.google.com/sdk/)

The command below will enable your Google shell terminal to act at a docker terminal
```
@FOR /f "tokens=*" %i IN ('docker-machine env --shell cmd default') DO @%i
```
This will link your docker and google account.
You need to have permission from the admin to be able to download the image.
```
docker login -u oauth2accesstoken -p "$(gcloud auth application-default print-access-token)" eu.gcr.io
```

Now you can download the image
```
gcloud docker -- pull eu.gcr.io/differ-140008/differ-service-bottyboys-knowledge-app:latest
```


### Run the Docker locally
This will run the docker image locally.
```
docker run -p "3000:3000" name_of_container
```

You can change the environments by using this command. 
Change `EDDI_API_URL=localhost7070` to whatever path you like.
```
docker run -e  "EDDI_API_URL=localhost7070" -p "3000:3000" name_of_container
```

This image is suppose to work with [**EDDI**](https://github.com/labsai/EDDI).
You will need it to be running in the background with bots added to it, in order for it to run properly
