const webpack = require('webpack');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const UglifyJSPlugin = require('uglifyjs-webpack-plugin');
const GenerateJsonPlugin = require('generate-json-webpack-plugin');
const _ = require('lodash');

const isBuild = process.env.npm_lifecycle_event === 'build';

export const isVendorModule = (module) => {
  // returns true for everything in node_modules
  return module.context && module.context.indexOf('node_modules') !== -1;
};

const ENV = process.env.NODE_ENV || 'local';

const defaultEnvironment = require(`../environments/${ENV}.json`);

const appVersion = require('../package.json').version;

export function getPlugins() {
  let plugins = [

    new webpack.ProgressPlugin(),

    new HtmlWebpackPlugin({
      template: 'public/index.html',
      favicon: 'public/favicon.ico'
    }),

    new webpack.EnvironmentPlugin(_.defaults({appVersion: appVersion}, {eddiApiUrl: process.env.EDDI_API_URL},
      {authMethod: process.env.AUTH_METHOD}, {authRealm: process.env.AUTH_REALM}, {authUrl: process.env.AUTH_URL},
      {authClientId: process.env.AUTH_CLIENT_ID}, {readOnlyDomain: process.env.READ_ONLY_DOMAIN}, defaultEnvironment)),

    new webpack.DefinePlugin({
      '__DEV__': JSON.stringify(ENV !== 'production' && ENV !== 'staging'),
    }),

    new webpack.HotModuleReplacementPlugin(),
  ];
  if (ENV === 'default') {
    // Add module names so they appear in browser profiler
    plugins.push(new webpack.NamedModulesPlugin());
  }

  if (isBuild) {
    plugins.push(new UglifyJSPlugin({sourceMap: true}));
    plugins.push(new GenerateJsonPlugin('version.json', {
      buildNumber: process.env.CIRCLE_BUILD_NUM,
      environment: ENV,
      version: appVersion,
    }));
  }

  return plugins;
}
