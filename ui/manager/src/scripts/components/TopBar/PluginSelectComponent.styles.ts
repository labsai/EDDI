import { makeStyles } from '@material-ui/core/styles';
import {
  DARK_GREY_COLOR,
  GREY_COLOR,
  LIGHT_BLUE_COLOR,
  LIGHT_GREY_COLOR,
  LIGHT_GREY_COLOR2,
  LIGHT_BLUE_COLOR3,
  SMALL_FONT2,
  WHITE_COLOR,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  indicatorsContainer: {
    position: 'relative',
    borderLeft: '6px solid transparent',
    borderRight: '6px solid transparent',
    borderTop: `6px solid ${DARK_GREY_COLOR}`,
    height: '0',
    marginRight: '10px',
    marginTop: '6px',
    width: '0',
  },
  input: {
    maxWidth: '60px',
    overflow: 'hidden',
    '&:active': {
      outline: '0',
    },
  },
  valueContainer: {
    fontSize: SMALL_FONT2,
    overflow: 'hidden',
    marginLeft: '1px',
    marginTop: '6px',
  },
  option: {
    color: DARK_GREY_COLOR,
    fontSize: SMALL_FONT2,
    overflow: 'hidden',
    textAlign: 'left',
    '&:hover': {
      backgroundColor: LIGHT_GREY_COLOR,
    },
  },
  selectContainer: {
    width: '170px',
  },
  control: {
    display: 'flex',
    height: '39px',
    backgroundColor: WHITE_COLOR,
    borderRadius: '0',
    border: '0',
    borderBottom: `3px solid ${LIGHT_GREY_COLOR2}`,
    '&:hover': {
      cursor: 'pointer',
      backgroundColor: LIGHT_BLUE_COLOR3,
      borderBottom: `3px solid ${GREY_COLOR}`,
    },
  },
  controlSelected: {
    borderBottom: `3px solid ${LIGHT_BLUE_COLOR}`,
  },
  singleValue: {
    color: GREY_COLOR,
  },
  singleValueSelected: {
    color: DARK_GREY_COLOR,
  },
});

export default useStyles;
