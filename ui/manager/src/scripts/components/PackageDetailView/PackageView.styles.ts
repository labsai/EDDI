import { createStyles, makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR,
  WHITE_COLOR,
  LARGE_FONT3,
  RED_COLOR,
  SMALL_FONT,
  SMALL_FONT2,
  GREY_COLOR2,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles(() =>
  createStyles({
    packageHeader: {
      display: 'flex',
      flex: 1,
      alignItems: 'center',
      marginTop: '47px',
    },
    packageHeaderSpacing: {
      flexGrow: 1,
    },
    packageName: {
      color: WHITE_COLOR,
      fontSize: LARGE_FONT3,
      marginRight: '20px',
      textAlign: 'left',
      maxWidth: '400px',
      overflow: 'hidden',
      whiteSpace: 'nowrap',
      textOverflow: 'ellipsis',
    },
    pluginAddTitle: {
      marginBottom: '10px',
      fontSize: SMALL_FONT,
      color: GREY_COLOR2,
    },
    pluginDropdown: {
      paddingBottom: '450px',
    },
    unpublishedChanges: {
      display: 'flex',
    },
    unpublishedChangesText: {
      color: RED_COLOR,
      fontSize: SMALL_FONT,
      marginLeft: '5px',
    },
    warningIcon: {
      height: '14px',
      marginLeft: '25px',
      marginTop: '9px',
    },
    editPackageButton: {
      marginLeft: '10px',
    },
    pluginList: {
      display: 'grid',
      marginTop: '20px',
      marginBottom: '20px',
      gridGap: '20px',
      gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
      minHeight: '5px',
      minWidth: '5px',
    },
    pluginListColumn: {
      display: 'flex',
      flexDirection: 'column',
    },
    discardChanges: {
      border: 'none',
      outline: 'none',
      color: WHITE_COLOR,
      cursor: 'pointer',
      fontSize: SMALL_FONT2,
      whiteSpace: 'nowrap',
      textAlign: 'right',
      marginRight: '5px',
      marginLeft: '10px',
      backgroundColor: 'transparent',

      '&:hover': {
        color: BLUE_COLOR,
      },
    },
    usedInBotsTitle: {
      color: WHITE_COLOR,
      fontSize: SMALL_FONT,
      marginTop: '20px',
    },
    options: {
      marginTop: 'auto',
      marginBottom: 'auto',
      marginRight: '5px',
    },
    greenButton: {
      backgroundColor: '#4BCA81',
      marginLeft: '10px',

      '&:hover': {
        backgroundColor: 'transparent',
        color: '#4BCA81',
        border: '2px solid #4BCA81',
      },
      '&:disabled': {
        backgroundColor: '#4BCA81',
        opacity: 0.6,
        border: 'none',
      },
      '&:active': {
        backgroundColor: '#4BCA81',
      },
    },
  }),
);

export default useStyles;
