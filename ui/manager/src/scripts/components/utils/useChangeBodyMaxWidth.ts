import * as React from 'react';

const useChangeBodyMaxWidth = (expanded: boolean): void => {
  React.useEffect(() => {
    if (expanded) {
      document.body.style.maxWidth = 'none';
    } else {
      document.body.style.maxWidth = '1080px';
    }

    return () => {
      document.body.style.maxWidth = '1080px';
    };
  }, [expanded]);
};

export default useChangeBodyMaxWidth;
