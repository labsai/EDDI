import { makeStyles } from '@material-ui/core/styles';
import {
  BLACK_COLOR,
  BLUE_COLOR,
  GREY_COLOR,
  WHITE_COLOR,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  chatContainer: {
    display: 'flex',
    flexDirection: 'column',
    width: '30vw',
    top: 0,
    right: 0,
    height: '100vh',
    position: 'fixed',
    border: `1px solid ${GREY_COLOR}`,
    overflow: 'auto',
    backgroundColor: BLACK_COLOR,
    paddingTop: '50px',
  },
  closeChat: {
    position: 'absolute',
    top: 15,
    right: 15,
    cursor: 'pointer',

    '&:hover svg': {
      color: BLUE_COLOR,
    },

    '& svg': {
      color: WHITE_COLOR,
    },
  },
  loader: {
    position: 'absolute',
    margin: 'auto',
    top: '50vh',
    left: '50%',
    right: '50%',
    backgroundColor: 'rgba(255, 255, 255, 0.5)',
  },
  error: {
    display: 'flex',
    alignSelf: 'center',
    color: GREY_COLOR,
  },
  emptyState: {
    display: 'flex',
    flexDirection: 'column',
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  emptyText: {
    color: GREY_COLOR,
    fontSize: '1rem',
  },
  chat: {
    display: 'flex',
    flexDirection: 'column',
    width: '100%',
    padding: '10px',
  },
  step: {
    display: 'flex',
    flexDirection: 'column',
    justifyContent: 'flex-start',
    alignItems: 'flex-start',
  },
});

export default useStyles;
