import * as React from 'react';

const useScrollIntoView = (
  ref: React.RefObject<HTMLElement>,
  data: any = undefined,
): void => {
  React.useEffect(() => {
    if (!ref || !ref?.current) {
      return;
    }
    ref.current.scrollIntoView({
      behavior: 'smooth',
    });
  }, [data]);
};

export default useScrollIntoView;
