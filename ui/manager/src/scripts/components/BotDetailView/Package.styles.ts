import { createStyles, makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR,
  WHITE_COLOR,
  LIGHT_GREY_COLOR2,
  LIGHT_BLUE_COLOR3,
  RED_COLOR,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles(() =>
  createStyles({
    editPackage: {
      border: 'none',
      outline: 'none',
      color: BLUE_COLOR,
      cursor: 'pointer',
      fontSize: '13px',
      textAlign: 'right',
      backgroundColor: 'transparent',
    },
    editPackageDisabled: {
      color: LIGHT_GREY_COLOR2,
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
      '&:hover': {
        backgroundColor: LIGHT_BLUE_COLOR3,
      },
      cursor: 'pointer',
      display: 'flex',
      alignItems: 'center',
      flex: 1,
    },
    packageName: {
      color: WHITE_COLOR,
      fontSize: '20px',
      marginRight: '20px',
      textAlign: 'left',
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
    version: {
      marginTop: 'auto',
      marginBottom: 'auto',
    },
    red: {
      color: 'red',
    },
    saveOrdering: { marginRight: '10px' },
    closeChangeOrdering: {
      color: 'white',
      backgroundColor: RED_COLOR,
      border: 'transparent',
      transition: 'background-color 0.3s ease',
      marginRight: '10px',

      '&:hover': {
        backgroundColor: 'transparent',
        border: `2px solid ${RED_COLOR}`,
        color: RED_COLOR,
        transition: 'background-color 0.3s ease',
      },
      '&:disabled': {
        cursor: 'not-allowed',
      },
    },
  }),
);

export default useStyles;
