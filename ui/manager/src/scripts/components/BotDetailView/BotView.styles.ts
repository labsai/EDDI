import { CSSProperties } from 'react';

const styles: CSSProperties = {
  botHeader: {
    display: 'flex',
    flex: '1',
    height: '35px',
    marginTop: '47px',
  },
  botHeaderSpacing: {
    flexGrow: '1',
  },
  botName: {
    color: '#16325C',
    fontSize: '28px',
    marginRight: '20px',
    textAlign: 'left',
    maxWidth: '350px',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
  },
  unpublishedChanges: {
    display: 'flex',
  },
  unpublishedChangesText: {
    color: '#FF5976',
    fontSize: '12px',
    marginLeft: '5px',
  },
  warningIcon: {
    height: '14px',
    marginLeft: '25px',
    marginTop: '9px',
  },
  button: {
    height: '38px',
    marginLeft: '10px',
    color: '#0070D2',
    fontSize: '12px',
    textAlign: 'center',
  },
  chatButton: {
    height: '39px',
    marginLeft: '10px',
  },
  deployButton: {
    height: '39px',
    marginLeft: '10px',
  },
};
export default styles;
