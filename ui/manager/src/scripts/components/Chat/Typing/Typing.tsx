import * as React from 'react';
import useScrollIntoView from '../../utils/useScrollIntoView';
import useStyles from './Typing.styles';

const Typing = () => {
  const classes = useStyles();
  const typingRef = React.useRef<HTMLDivElement>(null);

  useScrollIntoView(typingRef);
  return (
    <div id="typing" ref={typingRef} className={classes.ticontainer}>
      <div className={classes.tiblock}>
        <div className={classes.tidot} />
        <div className={classes.tidot} />
        <div className={classes.tidot} />
      </div>
    </div>
  );
};

export default Typing;
