import * as React from 'react';

const Delayed = ({ wait, children }: { wait: number; children: any }) => {
  const [hidden, setHidden] = React.useState(true);

  React.useEffect(() => {
    setTimeout(() => {
      setHidden(false);
    }, wait);
  }, []);

  return hidden ? null : children;
};

export default Delayed;
