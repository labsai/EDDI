import { makeStyles } from '@material-ui/core/styles';
import {
  DARK_GREY_COLOR,
  GREY_BORDER,
  GREY_COLOR,
  MEDIUM_FONT2,
  RED_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const styles = makeStyles({
  content: {
    color: GREY_COLOR,
    fontSize: '12px',
    width: '100%',
    textAlign: 'left',
    paddingBottom: '25px',
  },
  message: {
    margin: '20px auto 20px auto',
    color: GREY_COLOR,
    fontSize: '25px',
    width: '500px',
    textAlign: 'center',
  },
  buttons: {
    display: 'table',
    margin: '30px auto auto auto',
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
    fontSize: '24px',
    height: '32px',
    paddingTop: '30px',
    paddingBottom: '20px',
    marginLeft: 'auto',
    marginRight: 'auto',
  },
  input: {
    border: GREY_BORDER,
    borderRadius: '2px',
    display: 'block',
    margin: '5px auto 0 auto',
    width: '500px',
    fontSize: '20px',
    padding: '4px 0',
  },
  inputTitle: {
    display: 'block',
    margin: '5px auto 0 auto',
    width: '500px',
    fontSize: '20px',
  },
  warningIcon: {
    height: '30px',
    width: '30px',
  },
  error: {
    display: 'flex',
    width: '300px',
    color: RED_COLOR,
    fontSize: MEDIUM_FONT2,
    marginLeft: 'auto',
    marginRight: 'auto',
  },
  errorMessage: {
    marginLeft: '5px',
    marginTop: '6px',
  },
});
export default styles;
