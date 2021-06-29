import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import * as moment from 'moment';
import { makeStyles } from '@material-ui/core/styles';
import {
  WHITE_COLOR,
  GREY_COLOR,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  content: {
    color: WHITE_COLOR,
    fontSize: '13px',
    maxWidth: '300px',
    textAlign: 'left',
    overflow: 'hidden',
    wordBreak: 'break-word',
  },
  dateTime: {
    marginLeft: '65px',
  },
  descriptors: {
    display: 'flex',
    marginTop: '40px',
    overflow: 'hidden',
    width: '100%',
  },
  title: {
    color: GREY_COLOR,
    fontSize: '12px',
    height: '14px',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    width: 'fit-content',
  },
});

interface IProps {
  botCreated: number;
  botLastModified: number;
  botDescription: string;
}

const BotDescriptor: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div className={classes.descriptors}>
      <div>
        <div className={classes.title}>{'Description'}</div>
        <div className={classes.content}>{props.botDescription || 'N/A'}</div>
      </div>
      <div className={classes.dateTime}>
        <div className={classes.title}>{'Created'}</div>
        <div className={classes.content}>
          {moment(props.botCreated).format('DD.MM.YYYY')}
        </div>
      </div>
      <div className={classes.dateTime}>
        <div className={classes.title}>{'Last Modified'}</div>
        <div className={classes.content}>
          {moment(props.botLastModified).format('DD.MM.YYYY')}
        </div>
      </div>
    </div>
  );
};

const ComposedBotDescriptor: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('BotDescriptor'),
)(BotDescriptor);

export default ComposedBotDescriptor;
