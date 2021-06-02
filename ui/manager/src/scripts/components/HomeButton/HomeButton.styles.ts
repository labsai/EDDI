import { CSSProperties } from 'react';

const styles: { [key: string]: IExtendedCSSProperties } = {
  homeArrow: {
    borderBottom: '6px solid transparent',
    borderRight: '6px solid #A8B7C7',
    borderTop: '6px solid transparent',
    height: '0',
    marginTop: '5px',
    width: '0',
  },
  homeButton: {
    ':hover': {
      backgroundColor: '#F7F9FB',
    },
    borderRadius: '3px',
    cursor: 'pointer',
    display: 'flex',
    paddingBottom: '3px',
    paddingTop: '3px',
    textDecoration: 'none',
  },
  homeSquare: {
    backgroundColor: '#4A90E2',
    borderRadius: '7px',
    height: '23px',
    marginLeft: '7px',
    width: '23px',
  },
  homeText: {
    color: '#16325C',
    fontSize: '13px',
    marginLeft: '7px',
    paddingRight: '10px',
    marginTop: '4px',
    textAlign: 'left',
  },
  link: {
    display: 'flex',
    flex: 1,
    textDecoration: 'none',
  },
  navigationBar: {
    display: 'flex',
    marginTop: '40px',
  },
  navigationBarRightSide: {
    flex: 1,
  },
};
export default styles;
