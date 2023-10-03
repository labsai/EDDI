# Git support

## Git support

As part of **EDDI**'s features, **Git** is supported, this means you can `init`, `commit`, `push` and `pull` a **Chabot** to a **Git** repository.

## git`init` a Chatbot:

Exporting a bot is a fairly simple process, send a **`POST`** request to the following API endpoint and you will receive the location of the exported zip file on the response headers, specifically the **`location` header.**

### Export Chatbot REST API Endpoint

| Element      | Value                                                              |
| ------------ | ------------------------------------------------------------------ |
| Http Method  | `POST`                                                             |
| API endpoint | `/backup/export/{botId}?botVersion={botVersion}`                   |
| {botId}      | (`Path parameter`):`String` id of the bot that you wish to export. |
| {botVersion} | (`Path parameter`):`Integer` version of the bot.                   |

### **Example:**

_Request URL:_

`http://localhost:7070/backup/export/5aaf90e29f7dd421ac3c7dd4?botVersion=1`

_Response Body:_ `no content` _Response Code:_ `200` _Response Headers:_

```javascript
{
    "access-control-allow-origin": "*",
    "date": "Mon, 19 Mar 2018 10:41:41 GMT",
    "access-control-allow-headers": "authorization, Content-Type",
    "content-length": "0",
    "location": "http://localhost:7070/backup/export/5aaf90e29f7dd421ac3c7dd4-1.zip",
    "access-control-allow-methods": "GET, PUT, POST, DELETE, PATCH, OPTIONS",
    "content-type": null
}
```

## Importing a bot :

Same process as exporting; send a `POST` request to the following API endpoint , you will receive the id of the imported bot on the response headers.

### Import Chatbot REST API Endpoint

| Element                  | Value                  |
| ------------------------ | ---------------------- |
| Http Method              | `POST`                 |
| API endpoint             | `/backup/import`       |
| HTTP Content Type Header | `application/zip`      |
| Request body             | the `zip` file binary. |

### **Example**_**:**_

For the sake of simplifying things you can use [Postman](https://www.getpostman.com/) to upload the zip file of the exported bot just don't forget to add the **http header** of content type : _**`application/zip`****.**_

Take a look at he image below to understand how can you upload the zip file in Postman:

![](<.gitbook/assets/postman\_upload\_bin (1).png>)

> **Important:** The bot will not be deployed after import you will have to deploy it yourself by using the corresponding api endpoint, please referrer to [Deploying a bot](deployement-management-of-chatbots.md).
