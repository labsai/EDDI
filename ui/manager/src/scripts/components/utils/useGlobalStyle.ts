import * as React from 'react';

const reset = (style: HTMLStyleElement) => {
  document.head.removeChild(style);
};

export default (css) => {
  React.useEffect(() => {
    const style = document.createElement('style');
    style.setAttribute('type', 'text/css');
    style.innerHTML = css;
    document.head.appendChild(style);
    return () => reset(style);
  }, [css]);
};
