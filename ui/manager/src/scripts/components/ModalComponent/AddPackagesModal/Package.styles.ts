import { makeStyles } from '@material-ui/core/styles';
import {
  DARK_GREY_COLOR,
  GREEN_COLOR,
  GREY_COLOR,
  LARGE_FONT,
  LIGHT_GREY_COLOR,
  MEDIUM_FONT,
  MEDIUM_FONT2,
  MEDIUM_FONT3,
  SMALL_FONT2,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  packageName: {
    color: WHITE_COLOR,
    fontSize: MEDIUM_FONT3,
    marginLeft: '18px',
    marginRight: '10px',
    marginTop: '6px',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
  },
  centerFlex: {
    flex: 1,
  },
  modifiedDate: {
    color: LIGHT_GREY_COLOR,
    fontSize: SMALL_FONT2,
    marginTop: '2px',
  },
  content: {
    marginTop: '30px',
    marginLeft: '50px',
  },
  topContent: {
    display: 'flex',
    height: '36px',
  },
  button: {
    height: '36px',
    width: '36px',
    minHeight: '36px',
    minWidth: '36px',
    backgroundColor: GREY_COLOR,
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    fontSize: MEDIUM_FONT2,
    textAlign: 'center',
    cursor: 'pointer',
  },
  buttonChecked: {
    backgroundColor: GREEN_COLOR,
  },
  bottomContent: {
    marginTop: '5px',
    marginLeft: '54px',
    marginRight: '50px',
  },
  text: {
    maxWidth: '600px',
  },
  truncate: {
    color: LIGHT_GREY_COLOR,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  descriptionContainer: {
    display: 'flex',
    color: GREY_COLOR,
    fontSize: '13px',
    maxWidth: '600px',
  },
  descriptionButton: {
    fontSize: SMALL_FONT2,
    color: WHITE_COLOR,
    whiteSpace: 'nowrap',
  },
  versionSelect: {
    position: 'relative',
    marginRight: '10px',
    marginTop: 'auto',
    marginBottom: 'auto',
  },
  versionName: {
    backgroundColor: YELLOW_COLOR,
    borderRadius: '100px',
    color: WHITE_COLOR,
    fontSize: MEDIUM_FONT,
    height: '20px',
    marginTop: '6px',
    padding: '2px 10px',
    textAlign: 'center',
    width: '40px',
  },
  loading: {
    marginLeft: '50px',
    marginTop: '20px',
    marginBottom: '20px',
  },
  selectInput: {
    color: DARK_GREY_COLOR,
  },
});

export default useStyles;
