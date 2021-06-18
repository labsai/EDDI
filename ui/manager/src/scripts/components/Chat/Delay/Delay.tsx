import * as React from 'react';
import { useSelector } from 'react-redux';
import { getChatAnimation } from '../../../selectors/ChatSelectors';
import Typing from '../Typing/Typing';

const Delayed = ({
  wait,
  children,
  showTyping = false,
  ignoreAnimation = false,
}: {
  wait: number;
  children: any;
  showTyping?: boolean;
  ignoreAnimation?: boolean;
}) => {
  const [hidden, setHidden] = React.useState(true);
  const animation = useSelector(getChatAnimation);

  const hasAnimation = ignoreAnimation ? true : animation;

  const typingComponent = showTyping ? <Typing /> : null;

  React.useEffect(() => {
    const timer = setTimeout(
      () => {
        setHidden(false);
      },
      hasAnimation ? wait * 2 : 0,
    );

    return () => clearTimeout(timer);
  }, []);

  return hidden ? typingComponent : children;
};

export default Delayed;
