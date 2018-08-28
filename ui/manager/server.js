const express = require('express');
const serveStatic = require('serve-static');
const argv = require('minimist')(process.argv.slice(2));

const {port, path: hostPath, env} = argv;

const envUrl = '/_/env.json';

const app = express();

const session = require('express-session');
const Keycloak = require('keycloak-connect');

const memoryStore = new session.MemoryStore();
let keycloakConfig = {
  clientId: 'eddiui',
  bearerOnly: true,
  serverUrl: 'https://dev-auth.differ.chat/auth',
  realm: 'eddiui',
  realmPublicKey: 'MIIBIjANB...'
};

const keycloak = new Keycloak({ store: memoryStore }, keycloakConfig);

app.use(keycloak.middleware({}));
app.use(serveStatic(hostPath));

if (env) {

  try {
    JSON.parse(env);
    app.use(envUrl, (req, res) => {
      res.json(JSON.parse(env));
    });
  } catch (err) {
    console.error(`Failed to parse ${env} as JSON`, err);
    process.exit(1);
  }
  console.log(`Exposing environment ${env} on url ${envUrl}`);


}
app.listen(port);

console.log(`Server started on port ${port}`);
