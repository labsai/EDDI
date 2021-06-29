import { makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR2,
  WHITE_COLOR,
  GREY_COLOR,
  LARGE_FONT3,
  LIGHT_BLUE_COLOR2,
  LIGHT_GREY_BORDER,
  LIGHT_GREY_COLOR2,
  LIGHT_BLUE_COLOR3,
  MEDIUM_FONT2,
  RED_COLOR,
  SMALL_FONT2,
  YELLOW_COLOR,
  BLUE_COLOR,
  DARK_GREY_COLOR,
  BLACK_COLOR,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  content: {
    paddingBottom: '500px',
  },
  tabs: {
    display: 'flex',
    backgroundColor: BLACK_COLOR,
  },
  tab: {
    '&:hover': {
      color: BLUE_COLOR,
      borderBottom: `3px solid ${BLUE_COLOR}`,
    },
    backgroundColor: DARK_GREY_COLOR,
    marginTop: '30px',
    borderBottom: `3px solid ${BLUE_COLOR2}`,
    color: WHITE_COLOR,
    cursor: 'pointer',
    display: 'inline-block',
    fontSize: SMALL_FONT2,
    height: '39px',
    textAlign: 'center',
    flex: 1,
    lineHeight: '42px',
  },
  tabDisabled: {
    borderBottom: `3px solid ${LIGHT_GREY_COLOR2}`,
    color: GREY_COLOR,
  },
  header: {
    marginTop: '45px',
  },
  bottomHeader: {
    color: WHITE_COLOR,
    fontSize: SMALL_FONT2,
    display: 'flex',
    marginTop: '40px',
    overflow: 'hidden',
    width: '100%',
  },
  descriptor: {
    marginRight: '65px',
  },
  topHeader: {
    display: 'flex',
  },
  botName: {
    '&:hover': {
      color: BLUE_COLOR,
    },
    color: WHITE_COLOR,
    fontSize: LARGE_FONT3,
    textAlign: 'left',
    maxWidth: '350px',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    cursor: 'pointer',
  },
  botVersion: {
    backgroundColor: YELLOW_COLOR,
    borderRadius: '100px',
    color: WHITE_COLOR,
    fontSize: MEDIUM_FONT2,
    height: '22px',
    marginLeft: '15px',
    padding: '2px 12px',
    textAlign: 'center',
    width: 'fit-content',
    lineHeight: '19px',
    marginTop: '9px',
  },
  endConversationButton: {
    color: WHITE_COLOR,
    backgroundColor: RED_COLOR,
    width: '150px',
    marginLeft: 'auto',

    '&:disabled': {
      border: 'none',
      color: WHITE_COLOR,
    },
    '&:hover': {
      backgroundColor: 'transparent',
      border: `2px solid ${RED_COLOR}`,
      color: RED_COLOR,
      transition: 'background-color 0.3s ease, color 0.3s ease',
    },
  },

  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
  title: {},
  descriptorContent: {},
});

export default useStyles;
