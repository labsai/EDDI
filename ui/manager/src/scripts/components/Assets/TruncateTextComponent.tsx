import { ClassNameMap } from '@material-ui/styles/withStyles';
import * as React from 'react';
import { makeStyles } from '@material-ui/core/styles';
import { compose, pure, setDisplayName } from 'recompose';
import clsx from 'clsx';

const useStyles = makeStyles({
  truncate: {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    maxWidth: ({ length }: { length: number }) => `${length}ch`,
  },
  textContainer: {
    display: 'flex',
    color: '#7A849E',
    fontSize: '13px',
    whiteSpace: 'pre-wrap',
  },
  textButton: {
    fontSize: '13px',
    color: '#16325C',
    whiteSpace: 'nowrap',
  },
});

interface IProps {
  style?: React.CSSProperties;
  text: string;
  length: number;
  classes?: ClassNameMap;
}

const TruncateTextComponent = ({
  length,
  text,
  classes: externalClasses,
}: IProps) => {
  const classes = useStyles({ length });
  const [isExpanded, setisExpanded] = React.useState(false);

  const toggleText = () => {
    setisExpanded(!isExpanded);
  };

  return (
    <div>
      {!!text && (
        <div>
          <div className={clsx(classes.textContainer, externalClasses?.text)}>
            <div className={clsx({ [classes.truncate]: !isExpanded })}>
              {text}
            </div>
            {!isExpanded && text.length > length && (
              <a className={classes.textButton} onClick={toggleText}>
                {'See more'}
              </a>
            )}
          </div>
          {isExpanded && (
            <div>
              <a className={classes.textButton} onClick={toggleText}>
                {'See less'}
              </a>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const ComposedTruncateTextComponent: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('TruncateTextComponent'),
)(TruncateTextComponent);

export default ComposedTruncateTextComponent;
