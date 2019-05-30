import { output } from './webpack/output';
import { module } from './webpack/module';
import { getPlugins } from './webpack/plugins';
import { resolve } from './webpack/resolve';
import { devServer } from './webpack/dev-server';
import * as path from 'path';

const config = {
  context: path.join(process.cwd(), 'src'),
  devtool: 'source-map',
  entry: {
    app: 'scripts/index.tsx',
    vendor: ['babel-polyfill', 'react', 'react-dom'],
  },
  node: { fs: 'empty' },
  target: 'web',
  output,
  module,
  plugins: getPlugins(),
  resolve,
  devServer,
};

export { config as default };
