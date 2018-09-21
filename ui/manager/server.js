const express = require('express');
const serveStatic = require('serve-static');
const argv = require('minimist')(process.argv.slice(2));

const {port, path: hostPath, env} = argv;

const envUrl = '/_/env.json';

const app = express();

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
