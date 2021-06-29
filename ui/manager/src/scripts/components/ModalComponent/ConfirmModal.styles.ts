import { makeStyles } from '@material-ui/core/styles';
import {
  DARK_GREY_COLOR,
  LIGHT_GREY_COLOR,
  WHITE_COLOR,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  close: {
    '&:focus': {
      color: '#000',
      cursor: 'pointer',
    },
    '&:hover': {
      color: '#000',
      cursor: 'pointer',
    },
    color: '#FFF',
    cursor: 'pointer',
    float: 'right',
    fontSize: '40px',
    position: 'relative',
    top: '-40px',
  },
  content: {
    color: LIGHT_GREY_COLOR,
    fontSize: '12px',
    width: '100%',
    textAlign: 'left',
    paddingBottom: '25px',
  },
  message: {
    color: LIGHT_GREY_COLOR,
    fontSize: '18px',
    margin: '30px 20px 10px 20px',
    whiteSpace: 'pre-line',
    textAlign: 'center',
  },
  buttons: {
    display: 'table',
    margin: '80px auto auto auto',
  },
  modalHeader: {
    backgroundColor: DARK_GREY_COLOR,
    borderTopLeftRadius: '4px',
    borderTopRightRadius: '4px',
    width: '100%',
  },
  buttonMargin: {
    marginRight: '30px',
  },
  modalTopHeader: {
    color: WHITE_COLOR,
    display: 'table',
    fontSize: '20px',
    height: '32px',
    paddingTop: '30px',
    paddingBottom: '20px',
    marginLeft: 'auto',
    marginRight: 'auto',
  },
});

export default useStyles;
