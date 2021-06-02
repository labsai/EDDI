import * as React from 'react';
import Radium from 'radium';
import { CSSProperties } from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import * as moment from 'moment';

const styles: { [key: string]: IExtendedCSSProperties } = {
  content: {
    color: '#16325C',
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
    color: '#54698D',
    fontSize: '12px',
    height: '14px',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    width: 'fit-content',
  },
};

interface IProps {
  botCreated: number;
  botLastModified: number;
  botDescription: string;
}

const BotDescriptor: React.StatelessComponent<IProps> = (props: IProps) => (
  <div style={styles.descriptors}>
    <div>
      <div style={styles.title}>{'Description'}</div>
      <div style={styles.content}>{props.botDescription || 'N/A'}</div>
    </div>
    <div style={styles.dateTime}>
      <div style={styles.title}>{'Created'}</div>
      <div style={styles.content}>
        {moment(props.botCreated).format('DD.MM.YYYY')}
      </div>
    </div>
    <div style={styles.dateTime}>
      <div style={styles.title}>{'Last Modified'}</div>
      <div style={styles.content}>
        {moment(props.botLastModified).format('DD.MM.YYYY')}
      </div>
    </div>
  </div>
);

const ComposedBotDescriptor: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('BotDescriptor'),
)(BotDescriptor);

export default ComposedBotDescriptor;
