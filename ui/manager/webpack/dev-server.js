import * as path from 'path';

export const devServer = {
  host: '0.0.0.0',
  contentBase: path.join(process.cwd(), 'dist'),
  clientLogLevel: 'info',
  port: process.env.PORT,
  inline: true,
  hot: true,
  historyApiFallback: true,
  watchOptions: {
    aggregateTimeout: 300,
    poll: 500,
  },
};
