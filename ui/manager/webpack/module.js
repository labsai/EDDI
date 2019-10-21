const ExtractTextPlugin = require('extract-text-webpack-plugin');

const ENV = process.env.npm_lifecycle_event;
const isBuild = ENV === 'build';

const tsLoaderConfiguration = [
  'awesome-typescript-loader',
];

if (isBuild) {
  // only transpile es6 trough babel in builds
  tsLoaderConfiguration.unshift('babel-loader');
} else {
  // only include react-hot-loader runtime in development
  tsLoaderConfiguration.unshift('babel-loader');
  // todo: Fix this
  // tsLoaderConfiguration.unshift('react-hot-loader');
}

export const module = {
  rules: [
    {
      test: /\.tsx?$/,
      loaders: tsLoaderConfiguration,
    },
    {
      enforce: 'pre',
      test: /\.js$/,
      loader: 'source-map-loader',
      exclude: [],
    },
    {
      test: /\.scss$/,
      loader: ExtractTextPlugin.extract({fallback: 'style-loader', use: 'css-loader!sass-loader'}),
    },
    {
      test: /\.(png|jpe?g|gif)$/,
      loader: 'react-native-web-image-loader?name=[hash].[ext]',
    },
  ],
};
