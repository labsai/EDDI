import { makeStyles } from '@material-ui/core/styles';
import {
  BLACK_COLOR,
  GREY_COLOR,
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
    color: GREY_COLOR,
    fontSize: '18px',
    width: '100%',
    textAlign: 'left',
    paddingBottom: '10px',
  },
  message: {
    marginTop: '10px',
    whiteSpace: 'pre-line',
  },
  topContent: {
    marginLeft: '50px',
    marginRight: '50px',
    marginTop: '40px',
    display: 'flex',
  },
  button: {
    width: 'fit-content',
    margin: '60px auto auto auto',
  },
  modalHeader: {
    backgroundColor: BLACK_COLOR,
    borderTopLeftRadius: '4px',
    borderTopRightRadius: '4px',
    width: '100%',
  },
  modalTopHeader: {
    color: WHITE_COLOR,
    display: 'flex',
    fontSize: '18px',
    height: '32px',
    width: 'fit-content',
    paddingTop: '30px',
    paddingBottom: '20px',
    marginLeft: 'auto',
    marginRight: 'auto',
  },
  errorTitle: {
    fontSize: '22px',
    color: '#FF5976',
  },
  warningIcon: {
    height: '50px',
    marginRight: '15px',
  },
});
export default useStyles;
