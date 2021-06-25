import { createStyles, makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR,
  DARK_GREY_COLOR,
  LARGE_FONT3,
  RED_COLOR,
  SMALL_FONT,
  SMALL_FONT2,
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
      color: DARK_GREY_COLOR,
      fontSize: LARGE_FONT3,
      marginRight: '20px',
      textAlign: 'left',
      maxWidth: '400px',
      overflow: 'hidden',
      whiteSpace: 'nowrap',
      textOverflow: 'ellipsis',
    },
    pluginAddTitle: {
      marginBottom: '20px',
      fontSize: SMALL_FONT,
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
      color: BLUE_COLOR,
      cursor: 'pointer',
      fontSize: SMALL_FONT2,
      whiteSpace: 'nowrap',
      textAlign: 'right',
      backgroundColor: '#FFF',
      marginRight: '5px',
    },
    usedInBotsTitle: {
      color: DARK_GREY_COLOR,
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
        backgroundColor: '#4BCA81',
      },
      '&:active': {
        backgroundColor: '#4BCA81',
      },
    },
  }),
);

export default useStyles;
