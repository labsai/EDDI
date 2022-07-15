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

### Deploy project to Google Cloud Storage (https://levelup.gitconnected.com/how-to-deploy-react-applications-to-google-cloud-storage-59ac226409d6)

Install Google Cloud SDK (https://cloud.google.com/sdk/docs/install)
Download .tar.gz, extract and run

```
./google-cloud-sdk/install.sh
```

Ask for google cloud json file project owner/manager (eddi-199312-55e1244b735f.json)

This command will allow us to interact with our bucket
without the need of authenticating with our personal credentials, using our previously generated service account key

```
gcloud auth activate-service-account --key-file eddi-199312-55e1244b735f.json
```

Build project (files will be generated to dist folder)

```
yarn build
```

Sync up (deploy) our local files to our bucket

```
gsutil cp -r dist/* gs://manager-labs-ai
gsutil cp -p differ-140008 -r dist/* gs://bot-manager-differ
```

To show bucket info

```
gsutil ls -L -b gs://manager-labs-ai
```

To create a new bucket (gsutil mb -p PROJECT_ID gs://BUCKET_NAME)

```
gsutil mb -p differ-140008 gs://bot-manager-differ

```

To get bucket size

```
gsutil du -s gs://
```
