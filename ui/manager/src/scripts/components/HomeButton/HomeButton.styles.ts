import { makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR,
  DARK_GREY_COLOR,
  GREY_COLOR,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  homeArrow: {
    borderBottom: '6px solid transparent',
    borderRight: `6px solid ${GREY_COLOR}`,
    borderTop: '6px solid transparent',
    height: '0',
    marginTop: '5px',
    width: '0',
  },
  homeButton: {
    '&:hover': {
      backgroundColor: '#F7F9FB',
    },
    borderRadius: '3px',
    cursor: 'pointer',
    display: 'flex',
    paddingBottom: '3px',
    paddingTop: '3px',
    textDecoration: 'none',
  },
  homeSquare: {
    backgroundColor: BLUE_COLOR,
    borderRadius: '7px',
    height: '23px',
    marginLeft: '7px',
    width: '23px',
  },
  homeText: {
    color: DARK_GREY_COLOR,
    fontSize: '13px',
    marginLeft: '7px',
    paddingRight: '10px',
    marginTop: '4px',
    textAlign: 'left',
  },
  link: {
    display: 'flex',
    flex: 1,
    textDecoration: 'none',
  },
  navigationBar: {
    display: 'flex',
    marginTop: '40px',
  },
  navigationBarRightSide: {
    flex: 1,
  },
});
export default useStyles;
