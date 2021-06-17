import * as path from 'path';

export const output = {
  path: path.join(process.cwd(), 'dist'),
  filename: 'scripts/[name].[fullhash].js',
};
