module.exports = function(wallaby) {
  return {
    files: [
      'src/**/*.ts?(x)',
      {
        pattern: './package.json',
        instrument: false,
        load: true,
        ignore: false,
      },
      { pattern: 'tests/**/*', instrument: false, load: true, ignore: false },
      {
        pattern: 'tests/unit/tests/**/*',
        instrument: false,
        load: false,
        ignore: true,
      },
      {
        pattern: 'src/**/*.spec.ts?(x)',
        instrument: false,
        load: false,
        ignore: true,
      },
    ],
    tests: ['src/**/*.spec.ts?(x)', 'tests/unit/tests/**/*.ts?(x)'],
    compilers: {
      '**/*.ts?(x)': wallaby.compilers.typeScript({
        jsx: 'react',
        module: 'commonjs',
      }),
    },
    env: {
      type: 'node',
    },
    testFramework: 'mocha',
    setup: function() {
      require('./tests/unit/setup');
    },
  };
};
