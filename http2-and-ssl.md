# HTTP2 and SSL

Apart from regular `http 1.1`, **EDDI** also supports `http2`, thus connecting via `https`.

In the **github** `master` as well as the provided **Docker** containers on **Docker hub,** there is a **self-signed** `keystore` provided in order for **EDDI** to run `https` out-of-the-box when started up.

> Although, running **EDDI** with the provided `self-signed` certificate is absolutely fine for playing around with it locally, for **production** use it is highly **recommended to use your own certificate as the provided certificate is publicly available** and thus isn't considered to be safe!
>
> In order to make a https connection to third-party providers such as **Facebook Messenger**, you need to **provide a certificate signed by a trusted certification authority!**

In order to update the `keystore` in **EDDI** , you will need to create your own **Docker** `image` that has **EDDI** as its base image and override the provided `keystore` with your own.

The path of the `keystore` is: **`apiserver/resources/keystore/eddi_selfsigned.jks`**

In case you would like to change the name of you `keystore`, you will also need to alter the `keystore` name in the config value of **`webServer.keyStorePath`** in the properties file located here: **`apiserver/config/production/webServer.properties`**

As **EDDI** allows you to override the configs via VM params or environment variables, you could also pass on this param to the VM such as `-DwebServer.keyStorePath=<somePath>` .

For more information about this, refer to the [Getting Started](getting-started.md) page.

