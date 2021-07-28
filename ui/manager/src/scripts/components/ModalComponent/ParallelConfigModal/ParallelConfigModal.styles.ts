import { makeStyles } from '@material-ui/core/styles';
import {
  BLACK_COLOR,
  GREY_BORDER,
  GREY_COLOR,
  MEDIUM_FONT2,
  RED_COLOR,
  SMALL_FONT2,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const styles = makeStyles({
  modalContainer: {
    display: 'flex',
    flexDirection: 'column',
  },
  logsContainer: {
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
  },

  parallelConfigContainer: {
    color: GREY_COLOR,
    fontSize: '12px',
    textAlign: 'left',
    padding: '20px',
    maxWidth: '100%',
    height: '70vh',
    overflow: 'auto',
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
    backgroundColor: BLACK_COLOR,
    borderTopLeftRadius: '4px',
    borderTopRightRadius: '4px',
    width: '100%',
    display: 'flex',
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '0 20px',
  },
  actionButtons: {
    backgroundColor: BLACK_COLOR,
    width: '100%',
    display: 'flex',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    padding: '10px 20px',
  },
  buttonMargin: {
    marginRight: '30px',
  },
  modalTopHeader: {
    color: WHITE_COLOR,
    display: 'table',
    fontSize: '24px',
    height: '32px',
    paddingTop: '20px',
    paddingBottom: '10px',
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
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '400px',
    justifyContent: 'center',
  },
  empty: {
    color: WHITE_COLOR,
    textAlign: 'center',
    fontSize: SMALL_FONT2,
  },
  greenButton: {
    backgroundColor: '#4BCA81',
    marginLeft: '10px',

    '&:hover': {
      backgroundColor: 'transparent',
      color: '#4BCA81',
      border: '2px solid #4BCA81',
    },
    '&:active': {
      backgroundColor: '#4BCA81',
    },
    '&:disabled': {
      backgroundColor: '#4BCA81',
    },
  },
});
export default styles;
