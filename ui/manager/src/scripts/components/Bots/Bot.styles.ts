import { makeStyles } from '@material-ui/core/styles';
import {
  DARK_GREY_COLOR,
  GREY_COLOR,
  LIGHT_GREY_COLOR2,
  LIGHT_BLUE_COLOR3,
  MEDIUM_FONT,
  MEDIUM_FONT3,
  RED_COLOR,
  SMALL_FONT,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  botBox: {
    border: `1px solid ${LIGHT_GREY_COLOR2}`,
    borderRadius: '4px',
    marginBottom: '15px',
  },
  botContent: {
    marginLeft: '20px',
    flex: 1,
    paddingBottom: '10px',
  },
  botHeader: {
    backgroundColor: LIGHT_BLUE_COLOR3,
    borderRadius: '4px 4px 0 0',
    display: 'flex',
    height: '65px',
    width: '100%',
  },
  botHeaderCenter: {
    flex: 1,
  },
  botHeaderName: {
    color: DARK_GREY_COLOR,
    fontSize: MEDIUM_FONT3,
    height: '25px',
    marginLeft: '25px',
    marginTop: '15px',
    maxWidth: '300px',
    overflow: 'hidden',
    textDecoration: 'none',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  botIDNumber: {
    display: 'none',
    height: '20px',
    marginLeft: '25px',
    marginTop: '22px',
    maxWidth: '300px',
  },
  lastModified: {
    color: GREY_COLOR,
    float: 'right',
    fontSize: SMALL_FONT,
    height: '20px',
    marginTop: '24px',
    position: 'relative',
    whiteSpace: 'nowrap',
    width: '160px',
  },
  lastModifiedDate: {
    fontWeight: 'bold',
  },
  link: {
    display: 'flex',
    flex: 1,
    textDecoration: 'none',
    cursor: 'pointer',
    '&:hover': {
      textDecoration: 'underline',
    },
  },
  deployButton: {
    height: '35px',
    width: '108px',
    marginRight: '15px',
    marginTop: '17px',
  },
  chatButton: {
    height: '35px',
    width: '108px',
    marginLeft: '0',
    marginRight: '10px',
    marginTop: '17px',
  },
  updateAvailable: {
    color: RED_COLOR,
    fontSize: SMALL_FONT,
    height: '20px',
    marginLeft: '5px',
    marginTop: '27px',
  },
  versionName: {
    backgroundColor: LIGHT_GREY_COLOR2,
    borderRadius: '100px',
    color: DARK_GREY_COLOR,
    fontSize: MEDIUM_FONT,
    height: '21px',
    marginLeft: '15px',
    marginRight: '10px',
    marginTop: '20px',
    padding: '2px 10px',
    textAlign: 'center',
    width: 'fit-content',
    lineHeight: '18px',
  },
  warning: {
    display: 'flex',
  },
  warningIcon: {
    height: '14px',
    marginLeft: '15px',
    marginTop: '26px',
  },
  optionsMenu: {
    marginTop: 'auto',
    marginBottom: 'auto',
    marginRight: '5px',
    height: 'fit-content',
  },
});

export default useStyles;
