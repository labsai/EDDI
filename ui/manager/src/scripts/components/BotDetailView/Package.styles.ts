import { CSSProperties } from 'react';

const styles: CSSProperties = {
  editPackage: {
    border: 'none',
    outline: 'none',
    color: '#1589EE',
    cursor: 'pointer',
    fontSize: '13px',
    textAlign: 'right',
    backgroundColor: '#FFF',
  },
  editPackageDisabled: {
    color: '#D8DDE6',
    cursor: 'default',
  },
  centerFlex: {
    flexGrow: '1',
  },
  pack: {
    marginTop: '20px',
  },
  packageContent: {},
  packageHeader: {
    display: 'flex',
    flex: '1',
  },
  packageName: {
    color: '#16325C',
    fontSize: '20px',
    marginRight: '20px',
    textAlign: 'left',
    marginTop: '5px',
  },
  warningIcon: {
    height: '14px',
    marginLeft: '9px',
    marginTop: '9px',
  },
  updateAvailable: {
    color: '#FF5976',
    fontSize: '12px',
    height: '20px',
    marginLeft: '5px',
    marginTop: '8px',
  },
  warning: {
    display: 'flex',
  },
};
export default styles;
