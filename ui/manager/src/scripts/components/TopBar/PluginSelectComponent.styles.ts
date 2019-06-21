import { CSSProperties } from 'react';
import {
  DARK_BLUE_COLOR,
  GREY_COLOR,
  LIGHT_BLUE_COLOR,
  LIGHT_GREY_COLOR,
  LIGHT_GREY_COLOR2,
  LIGHT_GREY_COLOR3,
  SMALL_FONT2,
  WHITE_COLOR,
} from '../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  indicatorsContainer: {
    position: 'relative',
    borderLeft: '6px solid transparent',
    borderRight: '6px solid transparent',
    borderTop: '6px solid ' + DARK_BLUE_COLOR,
    height: '0',
    marginRight: '10px',
    width: '0',
  },
  input: {
    maxWidth: '60px',
    overflow: 'hidden',
    ':active': {
      outline: '0',
    },
  },
  valueContainer: {
    fontSize: SMALL_FONT2,
    overflow: 'hidden',
    marginLeft: '1px',
    marginTop: '4px',
  },
  option: {
    color: DARK_BLUE_COLOR,
    fontSize: SMALL_FONT2,
    overflow: 'hidden',
    textAlign: 'left',
    ':hover': {
      backgroundColor: LIGHT_GREY_COLOR,
    },
  },
  selectContainer: {
    width: '170px',
  },
  control: {
    display: 'flex',
    height: '42px',
    backgroundColor: WHITE_COLOR,
    borderRadius: '0',
    border: '0',
    borderBottom: '3px solid ' + LIGHT_GREY_COLOR2,
    ':hover': {
      cursor: 'pointer',
      backgroundColor: LIGHT_GREY_COLOR3,
      borderBottom: '3px solid ' + GREY_COLOR,
    },
  },
  controlSelected: {
    borderBottom: '3px solid ' + LIGHT_BLUE_COLOR,
  },
  singleValue: {
    color: GREY_COLOR,
  },
  singleValueSelected: {
    color: DARK_BLUE_COLOR,
  },
};

export default styles;
