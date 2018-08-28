import { CSSProperties } from 'react';

const styles: CSSProperties = {
  button: {
    ':hover': {
      backgroundColor: '#F4F6F9',
    },
    ':active': {
      backgroundColor: '#FFFFFF',
    },
    backgroundColor: '#FFFFFF',
    border: '0px solid #D8DDE6',
    color: '#16325C',
    display: 'block',
    fontSize: '12px',
    height: '30px',
    outline: 'none',
    width: '100%',
  },
  dropDown: {
    ':hover': {
      backgroundColor: '#F4F6F9',
      borderRadius: '0px',
      zIndex: '-1',
    },
    border: '1px solid #D8DDE6',
    borderRadius: '4px',
    cursor: 'pointer',
    display: 'inline-block',
    height: '32px',
    width: '80px',
  },
  dropDownArrowDown: {
    borderLeft: '6px solid transparent',
    borderRight: '6px solid transparent',
    borderTop: '6px solid #16325C',
    height: '0',
    marginLeft: '12px',
    marginTop: '3px',
    width: '0',
  },
  dropDownArrowUp: {
    borderBottom: '6px solid #16325C',
    borderLeft: '6px solid transparent',
    borderRight: '6px solid transparent',
    height: '0',
    marginLeft: '12px',
    marginTop: '3px',
    width: '0',
  },
  dropDownContent: {
    border: '1px solid #D8DDE6',
    left: '-1px',
    marginTop: '4px',
    position: 'relative',
    width: '80px',
  },
  dropDownSelected: {
    display: 'flex',
    marginLeft: '17px',
    marginTop: '9px',
  },
  dropDownSelectedVersion: {
    color: '#16325C',
    fontSize: '12px',
  },
};
export default styles;
