import { CSSProperties } from 'react';

const styles: CSSProperties = {
  indicatorsContainer: {
    position: 'relative',
    borderLeft: '6px solid transparent',
    borderRight: '6px solid transparent',
    borderTop: '6px solid #16325C',
    height: '0',
    marginRight: '10px',
    width: '0',
  },
  input: {
    maxWidth: '60px',
    overflow: 'hidden',
    ':active': {
      outline: '0',
    },
  },
  valueContainer: {
    fontSize: '14px',
    overflow: 'hidden',
    marginLeft: '1px',
  },
  option: {
    color: '#16325C',
    fontSize: '14px',
    overflow: 'hidden',
    textAlign: 'left',
    ':hover': {
      backgroundColor: '#F7F9FB',
    },
  },
  selectContainer: {
    width: '170px',
  },
  control: {
    display: 'flex',
    height: '42px',
    backgroundColor: '#FFF',
    borderRadius: '0',
    border: '0',
    borderBottom: '3px solid #E0E5EE',
    ':hover': {
      cursor: 'pointer',
      backgroundColor: '#F7F9FB',
    },
  },
  controlSelected: {
    borderBottom: '3px solid #4A90E2',
  },
  singleValue: {
    color: '#7A849E',
  },
  singleValueSelected: {
    color: '#16325C',
  },
};

export default styles;
