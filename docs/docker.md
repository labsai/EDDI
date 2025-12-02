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

### Environment Variables

You can configure EDDI using environment variables. This is especially useful for configuring AI tools that require API keys.

**Web Search Tool (Google):**
```bash
-e EDDI_TOOLS_WEBSEARCH_PROVIDER=google \
-e EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY=your_key \
-e EDDI_TOOLS_WEBSEARCH_GOOGLE_CX=your_cx
```

**Weather Tool (OpenWeatherMap):**
```bash
-e EDDI_TOOLS_WEATHER_OPENWEATHERMAP_API_KEY=your_key
```

Example with tools enabled:
```bash
docker run --name eddi \
  --link mongodb:mongodb \
  -p 7070:7070 \
  -e EDDI_TOOLS_WEBSEARCH_PROVIDER=google \
  -e EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY=AIzaSy... \
  -e EDDI_TOOLS_WEBSEARCH_GOOGLE_CX=012345... \
  -d labsai/eddi
```
