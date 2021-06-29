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
  modalContainer: {
    display: 'flex',
    flexDirection: 'column',
  },
  logsContainer: {
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
  },

  content: {
    color: GREY_COLOR,
    fontSize: '12px',
    textAlign: 'left',
    padding: '20px',
    maxWidth: '100%',
    backgroundColor: '#40424a',
    margin: '0 20px 20px',
    height: '60vh',
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
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '400px',
    justifyContent: 'center',
  },
  logItem: {
    color: WHITE_COLOR,
    wordBreak: 'break-word',
    padding: '10px 0',
    fontFamily:
      "'Monaco', 'Menlo', 'Ubuntu Mono', 'Consolas', 'source-code-pro', monospace",

    '& strong': {
      color: GREY_COLOR,
    },
  },
});
export default styles;
