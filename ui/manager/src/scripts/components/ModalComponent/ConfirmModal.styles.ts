import { CSSProperties } from 'react';

const styles: { [key: string]: IExtendedCSSProperties } = {
  close: {
    ':focus': {
      color: '#000',
      cursor: 'pointer',
    },
    ':hover': {
      color: '#000',
      cursor: 'pointer',
    },
    color: '#FFF',
    cursor: 'pointer',
    float: 'right',
    fontSize: '40px',
    position: 'relative',
    top: '-40px',
  },
  content: {
    color: '#54698D',
    fontSize: '12px',
    width: '100%',
    textAlign: 'left',
    paddingBottom: '25px',
  },
  message: {
    color: '#54698D',
    fontSize: '18px',
    margin: '30px 20px 10px 20px',
    whiteSpace: 'pre-line',
    textAlign: 'center',
  },
  buttons: {
    display: 'table',
    margin: '80px auto auto auto',
  },
  modalHeader: {
    backgroundColor: '#F7F9FB',
    width: '100%',
  },
  buttonMargin: {
    marginRight: '30px',
  },
  modalTopHeader: {
    display: 'table',
    fontSize: '20px',
    height: '32px',
    paddingTop: '30px',
    paddingBottom: '20px',
    marginLeft: 'auto',
    marginRight: 'auto',
  },
};
export default styles;
