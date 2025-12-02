# Import/Export a Chatbot

## Overview

**Import/Export** functionality allows you to package entire bots (including all their dependencies) into portable ZIP files. This is essential for bot lifecycle management, collaboration, and deployment automation.

### Why Import/Export?

**Use Cases**:
- **Backup & Restore**: Protect your bot configurations from accidental deletion or corruption
- **Version Control**: Store bot configurations alongside code in Git
- **Environment Migration**: Move bots from development → staging → production
- **Team Collaboration**: Share bots with team members or customers
- **Disaster Recovery**: Quickly restore bots after system failures
- **Bot Templates**: Create reusable bot templates for similar use cases
- **CI/CD Integration**: Automate bot deployment in your pipeline

### What Gets Exported?

When you export a bot, EDDI packages:
- ✅ Bot configuration (package references)
- ✅ All packages used by the bot
- ✅ All extensions (behavior rules, dictionaries, HTTP calls, outputs, etc.)
- ✅ Version information
- ✅ Configuration metadata

**Note**: Conversations and conversation history are **NOT** exported (only configurations).

### Export/Import Workflow

```
DEVELOPMENT EDDI
    ↓
1. Export Bot
   POST /backup/export/bot123?botVersion=1
   ← Returns: bot123-1.zip
    ↓
2. Download ZIP file
   GET /backup/export/bot123-1.zip
   ← Receives: bot123-1.zip file
    ↓
3. Store in version control / backup / transfer
    ↓
PRODUCTION EDDI
    ↓
4. Upload ZIP file
   POST /backup/import
   Body: (multipart/form-data with ZIP file)
   ← Returns: New bot ID
    ↓
5. Deploy imported bot
   POST /administration/unrestricted/deploy/{newBotId}?version=1
```

### Best Practices

- **Version Your Exports**: Include version numbers in filenames: `customer-support-bot-v2.3.zip`
- **Document Changes**: Keep a changelog of what changed between exports
- **Regular Backups**: Schedule automated exports of production bots
- **Test Imports**: Always test imported bots in a test environment first
- **Store Securely**: Keep exports in secure, version-controlled storage (e.g., Git LFS, S3)

### Common Scenarios

**Scenario 1: Promoting to Production**
```bash
# 1. Export from test environment
curl -X POST http://test.eddi.com/backup/export/bot123?botVersion=1

# 2. Download the ZIP
curl -O http://test.eddi.com/backup/export/bot123-1.zip

# 3. Import to production
curl -X POST -F "file=@bot123-1.zip" http://prod.eddi.com/backup/import

# 4. Deploy in production
curl -X POST http://prod.eddi.com/administration/restricted/deploy/{newBotId}?version=1
```

**Scenario 2: Sharing Bot with Team**
```bash
# Export bot
curl -X POST http://localhost:7070/backup/export/bot123?botVersion=1

# Commit to Git
git add bot123-1.zip
git commit -m "Added customer support bot v1"
git push

# Team member pulls and imports
git pull
curl -X POST -F "file=@bot123-1.zip" http://localhost:7070/backup/import
```

**Scenario 3: Disaster Recovery**
```bash
# Regular automated backup (cron job)
#!/bin/bash
DATE=$(date +%Y%m%d)
curl -X POST http://prod.eddi.com/backup/export/bot123?botVersion=1
curl -O http://prod.eddi.com/backup/export/bot123-1.zip
mv bot123-1.zip "backups/bot123-$DATE.zip"
aws s3 cp "backups/bot123-$DATE.zip" s3://bot-backups/

# Restore after failure
aws s3 cp s3://bot-backups/bot123-20250103.zip ./
curl -X POST -F "file=@bot123-20250103.zip" http://prod.eddi.com/backup/import
```

## API Reference

In this tutorial we will explain **importing/exporting** bots. This is a very useful feature that makes bots portable and easy to re-use across different EDDI instances, and allows you to backup, restore, and maintain your bots.

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

Take a look at the image below to understand how you can upload the zip file in Postman:

![](<.gitbook/assets/postman\_upload\_bin (3).png>)

> **Important:** The bot will not be deployed after import you will have to deploy it yourself by using the corresponding API endpoint, please refer to [Deploying a bot.](deployment-management-of-chatbots.md)
