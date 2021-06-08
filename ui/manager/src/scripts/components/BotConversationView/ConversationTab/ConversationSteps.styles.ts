import { makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR,
  DARK_BLUE_BORDER,
  DARK_BLUE_COLOR,
  GREY_BORDER,
  LIGHT_BLUE_COLOR,
  LIGHT_GREY_COLOR,
  MEDIUM_FONT3,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  toggleBox: {
    display: 'flex',
  },
  button: {
    '&:hover': {
      backgroundColor: LIGHT_GREY_COLOR,
    },
    position: 'relative',
    top: '2px',
    border: GREY_BORDER,
    width: '21px',
    height: '21px',
    borderRadius: '3px',
    marginLeft: '4px',
    color: GREY_BORDER,
    cursor: 'pointer',
  },
  toggleText: {
    marginTop: '3px',
    marginLeft: '2px',
  },
  icon: {
    display: 'table',
    height: '15px',
    width: '15px',
    marginTop: '2px',
    marginLeft: 'auto',
    marginRight: 'auto',
  },
  conversationSettings: {
    display: 'flex',
    marginBottom: '5px',
  },
  refresh: {
    '&:hover': {
      backgroundColor: LIGHT_BLUE_COLOR,
    },
    position: 'relative',
    top: '2px',
    backgroundColor: BLUE_COLOR,
    width: '40px',
    height: '21px',
    borderRadius: '3px',
    marginLeft: '6px',
    border: GREY_BORDER,
    cursor: 'pointer',
  },
  toolbar: {
    display: 'table',
  },
  title: {
    fontSize: MEDIUM_FONT3,
    color: DARK_BLUE_COLOR,
    textAlign: 'center',
    margin: '20px 0px 20px 0px',
    borderBottom: DARK_BLUE_BORDER,
  },
});

export default useStyles;
