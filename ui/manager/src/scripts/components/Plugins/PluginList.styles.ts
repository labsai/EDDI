import { makeStyles } from '@material-ui/core/styles';
import {
  DARK_GREY_COLOR,
  GREY_COLOR,
  LARGE_FONT3,
  SMALL_FONT,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
  topHeader: {
    display: 'flex',
  },
  title: {
    color: DARK_GREY_COLOR,
    fontSize: LARGE_FONT3,
  },
  lastModified: {
    color: GREY_COLOR,
    fontSize: SMALL_FONT,
    marginLeft: 'auto',
    marginRight: '0',
    marginTop: '14px',
  },
  pluginList: {
    paddingBottom: '250px',
  },
});

export default useStyles;
