import {
  BLUE_COLOR,
  BLUE_COLOR_TRANSPARENT,
  DARK_GREY_COLOR,
  GREY_BORDER,
  GREY_COLOR3,
  LIGHTER_YELLOW_COLOR,
  LIGHT_BLUE_COLOR2,
  LIGHT_GREY_COLOR2,
  LIGHT_GREY_COLOR3,
  MEDIUM_FONT,
  MEDIUM_FONT3,
  SMALL_FONT,
  SMALL_FONT2,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import { makeStyles } from '@material-ui/core/styles';

export const useStyles = makeStyles({
  content: {
    '&:hover': {
      backgroundColor: YELLOW_COLOR,
    },
    flex: 1,
    fontSize: MEDIUM_FONT,
    borderRadius: '18px',
    width: '100%',
  },
  input: {
    backgroundColor: WHITE_COLOR,
    borderRadius: '18px',
    color: DARK_GREY_COLOR,
    display: 'table',
    padding: '8px 18px',
    marginLeft: 'auto',
    marginTop: '12px',
    wordWrap: 'break-word',
    maxWidth: '100%',
  },
  quickReplies: {
    marginLeft: '10px',
  },
  quickReply: {
    backgroundColor: GREY_COLOR3,
    borderRadius: '18px',
    color: WHITE_COLOR,
    display: 'inline-block',
    whiteSpace: 'pre',
    padding: '8px 18px',
    marginTop: '12px',
    marginRight: '10px',
  },
  output: {
    backgroundColor: LIGHT_BLUE_COLOR2,
    borderRadius: '18px',
    display: 'table',
    padding: '8px 18px',
    marginTop: '12px',
    marginLeft: '10px',
    wordWrap: 'break-word',
    maxWidth: '100%',
  },
  outputImage: {
    padding: '14px 28px',
    marginTop: '12px',
    maxWidth: '100%',
  },
  timeContainer: {
    margin: '0',
    width: '65px',
    display: 'flex',
  },
  timeArrow: {
    position: 'relative',
    margin: '22px 0 22px 5px',
    width: '20px',
    borderTop: `2px solid ${BLUE_COLOR}`,
    borderRight: `2px solid ${BLUE_COLOR}`,
    borderBottom: `2px solid ${BLUE_COLOR}`,
  },
  timeSpan: {
    margin: 'auto 0 auto 2px',
    width: '30px',
    fontSize: SMALL_FONT,
    color: WHITE_COLOR,
  },
  arrowLeft: {
    position: 'absolute',
    bottom: '-6px',
    right: '12px',
    width: 0,
    height: 0,
    borderTop: '5px solid transparent',
    borderBottom: '5px solid transparent',
    borderRight: `10px solid ${BLUE_COLOR}`,
  },
  container: {
    display: 'flex',
  },
  chatStep: {
    flex: 1,
    cursor: 'pointer',
    paddingBottom: '10px',
    paddingTop: '10px',
    minWidth: 0,
  },
  titleContainer: {
    '&:hover': {
      opacity: '0.5',
    },
    cursor: 'pointer',
    fontSize: MEDIUM_FONT3,
    color: WHITE_COLOR,
    backgroundColor: BLUE_COLOR,
    textAlign: 'center',
    padding: '5px 0',
    display: 'flex',
  },
  titleBox: {
    width: '50px',
  },
  titleText: {
    flex: 1,
  },
  actions: {
    fontSize: SMALL_FONT2,
    textAlign: 'center',
    paddingLeft: '65px',
  },
  actionTitle: {
    textDecoration: 'underline',
    color: BLUE_COLOR,
  },
  action: {
    color: BLUE_COLOR,
  },
  jsonView: {},
  icon: {},
});

export const rjvStyles = {
  rjv: {
    border: GREY_BORDER,
    borderRadius: '5px',
    fontSize: SMALL_FONT2,
  },
};
