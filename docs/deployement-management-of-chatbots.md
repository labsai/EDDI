# Deployement management of Chatbots

## Deployement management of Chatbots

In this section we will discuss the deployment management of Chatbots which consists of deployment/undeployment, checking status of deployment and list all the deployed Chatbots.

After all the resources required of the chatbot has been well created and configured ( **`Dictionary`** ,**`Behavior Rules`,`Output`,`Package`,etc..)** and the Chatbot is created through a **`POST`** to the API endpoint **`/botstore/bots`**, the deployment management of the Chatbots is offered by **EDDI** is key to have granular control over the deployed bots.

## **Deployment of a Chatbot :**

The deployment of a specific chatbot is done through a **`POST`** to **`/administration/{environment}/deploy/{botId}`**

### Deploy Chatbot REST API Endpoint

| Element       | Value                                                                                      |
| ------------- | ------------------------------------------------------------------------------------------ |
| HTTP Method   | `POST`                                                                                     |
| API endpoint  | `/administration/{environment}/deploy/{botId}`                                             |
| {environment} | (`Path parameter`):`String` deployment environment (e.g: **restricted,unrestricted,test**) |
| {botId}       | (`Path parameter`):`String` id of the bot that you wish to **deploy**.                     |

### Example _:_

_Request URL:_

`http://localhost:7070/administration/unrestricted/deploy/5aaf98e19f7dd421ac3c7de9?version=1&autoDeploy=true`

_Response Body:_

`no content`

_Response Code:_

`202`

_Response Headers:_

```javascript
{
"access-control-allow-origin": "*",
"date": "Mon, 19 Mar 2018 16:32:58 GMT",
"access-control-allow-headers": "authorization, Content-Type",
"content-length": "0",
"access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
"content-type": null
}
```

## **Undeployment of a Chatbot**

The undeployment of a specific chatbot is done through a **`POST`** to **`/administration/{environment}/undeploy/{botId}`**

### Undeploy Chatbot REST API Endpoint

| Element       | Value                                                                                      |
| ------------- | ------------------------------------------------------------------------------------------ |
| HTTP Method   | `POST`                                                                                     |
| API endpoint  | `/administration/{environment}/undeploy/{botId}`                                           |
| {environment} | (`Path parameter`):`String` deployment environment (e.g: **restricted,unrestricted,test**) |
| {botId}       | (`Path parameter`):`String` id of the bot that you wish to **undeploy**.                   |

### Example :

**Undeploy a chatbot**

_Request URL_

`http://localhost:7070/administration/unrestricted/undeploy/5aaf98e19f7dd421ac3c7de9?version=1`

_Response Body_

`no content`

_Response Code_

`202`

_Response Headers_

```javascript
{
"access-control-allow-origin": "*",
"date": "Mon, 19 Mar 2018 16:38:36 GMT",
"access-control-allow-headers": "authorization, Content-Type",
"content-length": "0",
"access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
"content-type": null

}
```

## **Check the deployment status of a chatbot:**

Check the deployment status of a bot is done through a **`GET`** to **`/administration/{environment}/deploymentstatus/{botId}`**

Deployment status of a Chatbot REST API Endpoint

| Element       | Value                                                                                           |
| ------------- | ----------------------------------------------------------------------------------------------- |
| HTTP Method   | `GET`                                                                                           |
| Api endpoint  | `/administration/{environment}/deploymentstatus/{botId}`                                        |
| {environment} | (`Path parameter`):`String` deployment environment (e.g: **restricted,unrestricted,test**)      |
| {botId}       | (`Path parameter`):`String` id of the bot that you wish to **check** its **deployment status**. |
| Response      | `NOT_FOUND`, `IN_PROGRESS`, `ERROR` and `READY`.                                                |

### Example_:_

_Request URL_

`http://localhost:7070/administration/unrestricted/deploymentstatus/5aaf98e19f7dd421ac3c7de9?version=1`

_Response Body_

`READY`

_Response Code_

`200`

_Response Headers_

## **List all deployed Chatbots:**

```javascript
{
"access-control-allow-origin": "*",
"date": "Mon, 19 Mar 2018 16:33:08 GMT",
"access-control-allow-headers": "authorization, Content-Type",
"content-length": "5",
"access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
"content-type": "text/plain;charset=utf-8"
}
```

To list all the deployed Chabots a `GET` to **`/administration/{environment}/deploymentstore/{botId}`**:

### List of Deployed Chatbots REST API Endpoint

| Element      | Value                          |
| ------------ | ------------------------------ |
| HTTP Method  | `GET`                          |
| API endpoint | `/deploymentstore/deployments` |

### Example :

_Request URL_

`http://localhost:7070/deploymentstore/deployments`

_Response Code_

`200`

_Response Body_

```javascript
[
  {
    "botId": "5aaf90e29f7dd421ac3c7dd4",
    "botVersion": 1,
    "environment": "unrestricted",
    "deploymentStatus": "deployed"
  },
  {
    "botId": "5aaf90e29f7dd421ac3c7dd4",
    "botVersion": 1,
    "environment": "restricted",
    "deploymentStatus": "deployed"
  },
  {
    "botId": "5aaf98e19f7dd421ac3c7de9",
    "botVersion": 1,
    "environment": "unrestricted",
    "deploymentStatus": "deployed"
  }
]
```

_Response Headers_

```javascript
{
"access-control-allow-origin": "*",
"date": "Mon, 19 Mar 2018 16:33:29 GMT",
"access-control-allow-headers": "authorization, Content-Type",
"content-length": "414",
"access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
"content-type": "application/json"
}
```
