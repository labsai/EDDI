import {
  DARK_GREY_COLOR,
  SMALL_FONT,
} from '../../../styles/DefaultStylingProperties';
import { makeStyles } from '@material-ui/core/styles';

const useStyles = makeStyles({
  title: {
    display: 'flex',
    color: DARK_GREY_COLOR,
    fontSize: SMALL_FONT,
    marginBottom: '2px',
  },
  stepSize: {
    marginLeft: 'auto',
    width: '80px',
    textAlign: 'center',
  },
  environment: {
    width: '80px',
    marginRight: '15px',
  },
  conversationState: {
    width: '110px',
    marginRight: '20px',
  },
  lastModifiedOn: {
    marginRight: '42px',
  },
  createdOn: {
    marginRight: '2px',
  },
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
  packageList: {},
});

export default useStyles;
