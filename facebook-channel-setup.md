# Facebook Channel Setup

## Facebook Channel Setup

## Prerequisites

In order to set up a bot including a **facebook** channel you need to have set up the following prerequisites:

1. Set up a Facebook Page [`https://www.facebook.com/business/learn/set-up-facebook-page`](https://www.facebook.com/business/learn/set-up-facebook-page)
2. Set up a Facebook App [`https://developers.facebook.com/docs/apps/register`](https://developers.facebook.com/docs/apps/register)
3. Go through the bot creation process but stop before the last step "[Creating you first ChatBot](creating-your-first-chatbot.md)"

### Creating the bot including the facebook channel

1. Go to [https://developers.facebook.com](https://developers.facebook.com/)
2. Log in with your **facebook** account and go to your already created app
3. Click on Add Product in the menu and select Messenger
4. Generate the page `access token`
5. Make a **POST** to **`/botstore/bots`** with a json like this:

   { "packages": \[ "eddi://ai.labs.package/packagestore/packages/?version=1" \], "channels": \[ { "type": "eddi://ai.labs.channel.facebook", "config": { "appSecret": "", "verificationToken": "", "pageAccessToken": "" } } \] }

6. Deploy your bot as described in the "[Creating you first ChatBot](creating-your-first-chatbot.md)" page!

After deployment your `webhook URL` will look like this:

`https://<hostname>/channels/facebook/`**`<BOT_ID>`**`?version=1`

> **VERY IMPORTANT!** The url needs to be `https` and the certificate needs to be an **actual certificate** of a valid certification agency. For free certificates have a look here: [`https://letsencrypt.org`](https://letsencrypt.org/)

1. Go to [`https://developers.facebook.com/apps/`](https://developers.facebook.com/apps/)
2. Click on messenger in the menu and open the `webhook` setup.
3. Enter your bot url and the `verification token` that you entered, when creating the bot and add messages as a subscription
4. Deploy your **facebook** app and the bot is live!

