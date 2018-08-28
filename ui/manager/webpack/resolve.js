import * as path from 'path';

export const resolve = {
  alias: {
    'images': 'public/images',
  },
  modules: [
    'node_modules',
    path.resolve(process.cwd(), 'src'),
  ],
  extensions: [
    '.ts',
    '.tsx',
    '.js',
    '.json',
    '.scss'
  ],
};
