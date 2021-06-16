import * as React from 'react';
import Typing from '../Typing/Typing';

const Delayed = ({
  wait,
  children,
  showTyping = false,
}: {
  wait: number;
  children: any;
  showTyping?: boolean;
}) => {
  const [hidden, setHidden] = React.useState(true);

  const typingComponent = showTyping ? <Typing /> : null;

  React.useEffect(() => {
    setTimeout(() => {
      setHidden(false);
    }, wait * 2);
  }, []);

  return hidden ? typingComponent : children;
};

export default Delayed;
