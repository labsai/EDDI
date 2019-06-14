const express = require('express');
const serveStatic = require('serve-static');
const argv = require('minimist')(process.argv.slice(2));
const path = require('path');

const {port, path: hostPath, env} = argv;

const envUrl = '/_/env.json';

const app = express();

const cors = require('cors');

app.use(cors());
app.use(serveStatic(hostPath));

if (env) {

  try {
    JSON.parse(env);
    app.use(envUrl, (req, res) => {
      res.json(JSON.parse(env));
    });

    // fallback to using the index.html. For example when the user reloads a different page than root,
    // we want to service the index.html so that the react routing can take over
    app.get('/*',(req, res) => {
      res.sendFile(path.join(__dirname, hostPath, 'index.html'));
    });
  } catch (err) {
    console.error(`Failed to parse ${env} as JSON`, err);
    process.exit(1);
  }
  console.log(`Exposing environment ${env} on url ${envUrl}`);


}
app.listen(port);

console.log(`Server started on port ${port}`);
