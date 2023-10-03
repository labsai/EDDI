# Import/Export a Chatbot

In this tutorial we will talk about **importing/exporting** bots, this is a very useful feature that will allow our bots to be very portable and easy to re-use in other machines/instances of **EDDI** and of course to backup and restore our bots and maintain them and keep them shiny.

## Exporting a Chatbot:

Exporting a bot is a fairly simple process, send a **`POST`** request to the following API endpoint and you will receive the location of the exported zip file on the response headers, specifically the **`location` header.**

Export Chatbot REST API Endpoint

{% swagger baseUrl="http://localhost:7070" path="/backup/export/:botId?botVersion=:botVersion" method="post" summary="Export a chatbot" %}
{% swagger-description %}

{% endswagger-description %}

{% swagger-parameter in="path" name="botId" type="string" %}
Id of the bot you wish to export
{% endswagger-parameter %}

{% swagger-parameter in="query" name="botVersion" type="string" %}
Version of the bot
{% endswagger-parameter %}

{% swagger-response status="200" description="" %}
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
{% endswagger-response %}
{% endswagger %}

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

## Importing a bot:

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

![](<.gitbook/assets/postman\_upload\_bin (3).png>)

> **Important:** The bot will not be deployed after import you will have to deploy it yourself by using the corresponding API endpoint, please referrer to [Deploying a bot.](deployement-management-of-chatbots.md)
