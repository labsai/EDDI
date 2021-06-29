import { makeStyles } from '@material-ui/core/styles';
import {
  WHITE_COLOR,
  GREY_COLOR,
  LARGE_FONT3,
  LIGHT_BLUE_COLOR3,
  SMALL_FONT,
  DARK_GREY_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  header: {
    height: '156px',
    backgroundColor: DARK_GREY_COLOR,
  },
  topHeader: {
    paddingTop: '55px',
    display: 'flex',
  },
  title: {
    color: WHITE_COLOR,
    fontSize: LARGE_FONT3,
    height: '36px',
    marginLeft: '50px',
  },
  centerFlex: {
    flex: 1,
  },
  button: {
    width: '165px',
    marginRight: '50px',
  },
  backButton: {
    width: '165px',
    marginRight: '5px',
  },
  createButton: {
    width: '165px',
    marginRight: '10px',
  },
  bottomHeader: {
    marginTop: '42px',
    display: 'flex',
  },
  lastModified: {
    marginRight: '50px',
    color: GREY_COLOR,
    fontSize: SMALL_FONT,
  },
  packageList: {
    marginLeft: '50px',
    marginRight: '50px',
    paddingBottom: '100px',
  },
  closeContainer: {
    display: 'flex',
    marginTop: '-40px',
  },
  closeContainerCenter: {
    flexGrow: 1,
  },
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
  loadMoreButton: {
    width: '150px',
    height: '40px',
    display: 'block',
    marginTop: '20px',
    marginLeft: 'auto',
    marginRight: 'auto',
  },
});

export default useStyles;
