import { CSSProperties } from 'react';
import { LIGHT_GREY_COLOR3 } from '../../../styles/DefaultStylingProperties';

const styles: { [key: string]: IExtendedCSSProperties } = {
  editPackage: {
    border: 'none',
    outline: 'none',
    color: '#1589EE',
    cursor: 'pointer',
    fontSize: '13px',
    textAlign: 'right',
    backgroundColor: 'transparent',
  },
  editPackageDisabled: {
    color: '#D8DDE6',
    cursor: 'default',
  },
  centerFlex: {
    flexGrow: 1,
  },
  pack: {
    marginTop: '20px',
  },
  packageContent: {},
  packageHeader: {
    ':hover': {
      backgroundColor: LIGHT_GREY_COLOR3,
    },
    cursor: 'pointer',
    display: 'flex',
    flex: 1,
  },
  packageName: {
    color: '#16325C',
    fontSize: '20px',
    marginRight: '20px',
    textAlign: 'left',
    marginTop: '6px',
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
  options: {
    marginTop: 'auto',
    marginBottom: 'auto',
    marginRight: '5px',
  },
};
export default styles;
