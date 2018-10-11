import { CSSProperties } from 'react';

const styles: CSSProperties = {
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
  },
  buttons: {
    width: 'fit-content',
    margin: '60px auto auto auto',
  },
  modalHeader: {
    backgroundColor: '#F7F9FB',
    width: '100%',
  },
  buttonMargin: {
    marginRight: '30px',
  },
  modalTopHeader: {
    fontSize: '18px',
    height: '32px',
    width: 'fit-content',
    paddingTop: '30px',
    paddingBottom: '20px',
    marginLeft: 'auto',
    marginRight: 'auto',
  },
};
export default styles;
