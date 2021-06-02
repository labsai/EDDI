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

interface IExtendedCSSProperties extends React.CSSProperties {
  ':hover'?: React.CSSProperties;
  ':disabled'?: React.CSSProperties;
  ':focus'?: React.CSSProperties;
  ':active'?: React.CSSProperties;
}
