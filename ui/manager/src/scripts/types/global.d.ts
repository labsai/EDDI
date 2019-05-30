declare const module: {
  hot: {
    accept: Function;
  };
  exports: any;
};

declare module '*.json' {
  const value: any;
  export default value;
}
