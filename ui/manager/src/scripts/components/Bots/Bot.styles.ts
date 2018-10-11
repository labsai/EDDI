import { CSSProperties } from 'react';

const styles: CSSProperties = {
  botBox: {
    border: '1px solid #E0E5EE',
    borderRadius: '4px',
    marginBottom: '15px',
  },
  botContent: {
    marginLeft: '20px',
    flex: 1,
    paddingBottom: '10px',
  },
  botHeader: {
    backgroundColor: '#F7F9FB',
    borderRadius: '4px 4px 0 0',
    display: 'flex',
    height: '65px',
    width: '100%',
  },
  botHeaderCenter: {
    flex: '1',
  },
  botHeaderName: {
    color: '#54698D',
    fontSize: '20px',
    height: '25px',
    marginLeft: '25px',
    marginTop: '20px',
    maxWidth: '200px',
    overflow: 'hidden',
    textDecoration: 'none',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  botIDNumber: {
    display: 'none',
    height: '20px',
    marginLeft: '25px',
    marginTop: '22px',
    maxWidth: '300px',
  },
  lastModified: {
    color: '#54698D',
    float: 'right',
    fontSize: '12px',
    height: '20px',
    marginTop: '24px',
    position: 'relative',
    whiteSpace: 'nowrap',
    width: '160px',
  },
  lastModifiedDate: {
    fontWeight: 'bold',
  },
  link: {
    display: 'flex',
    flex: '1',
    textDecoration: 'none',
  },
  publishButton: {
    height: '35px',
    width: '108px',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '12px',
    textAlign: 'center',
    marginLeft: '25px',
    marginRight: '25px',
    marginTop: '17px',
  },
  undeployButton: {
    color: 'white',
    backgroundColor: '#FF5976',
    border: '1px solid #FF5976',
    ':hover': {
      backgroundColor: 'red',
    },
  },
  deployButton: {
    color: 'white',
    backgroundColor: '#4BCA81',
    border: '1px solid #4BCA81',
    ':hover': {
      backgroundColor: 'green',
    },
  },
  inProgressButton: {
    color: 'white',
    backgroundColor: '#FADA5E',
    border: '1px solid #FADA5E',
  },
  errorButton: {
    color: 'red',
    backgroundColor: 'white',
    border: '1px solid #D8DDE6',
    cursor: 'default',
  },
  updateAvailable: {
    color: '#FF5976',
    fontSize: '12px',
    height: '20px',
    marginLeft: '5px',
    marginTop: '27px',
  },
  versionName: {
    backgroundColor: '#E0E5EE',
    borderRadius: '100px',
    color: '#16325C',
    fontSize: '16px',
    height: '20px',
    marginLeft: '15px',
    marginTop: '22px',
    padding: '2px 10px',
    textAlign: 'center',
    width: '40px',
  },
  warning: {
    display: 'flex',
  },
  warningIcon: {
    height: '14px',
    marginLeft: '15px',
    marginTop: '26px',
  },
};
export default styles;
